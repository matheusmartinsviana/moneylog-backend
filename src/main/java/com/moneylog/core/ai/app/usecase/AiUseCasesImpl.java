package com.moneylog.core.ai.app.usecase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moneylog.core.ai.app.AIService;
import com.moneylog.core.ai.app.AiFactsAssembler;
import com.moneylog.core.ai.app.AiGuard;
import com.moneylog.core.ai.app.AiRequest;
import com.moneylog.core.ai.app.AiResult;
import com.moneylog.core.ai.app.AiResult.AiCompleted;
import com.moneylog.core.ai.app.AiResult.AiUnavailable;
import com.moneylog.core.ai.app.AiResult.Reason;
import com.moneylog.core.ai.app.gateways.AiAnalysisGateway;
import com.moneylog.core.ai.app.gateways.AiPreviewGateway;
import com.moneylog.core.ai.app.gateways.AiSettingsGateway;
import com.moneylog.core.ai.app.usecase.AiUseCases.CreateAiPreviewUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.CreatePreviewCommand;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetAiAnalysisUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetAiPreviewUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetAiSettingsUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetAiStatusUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetInsightsUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.InsightsView;
import com.moneylog.core.ai.app.usecase.AiUseCases.ListAiAnalysesUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.PurgeExpiredAiDataUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.RunAnalysisCommand;
import com.moneylog.core.ai.app.usecase.AiUseCases.RunApprovedAnalysisUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.SaveAiSettingsUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.StatusView;
import com.moneylog.core.ai.domain.AiAnalysis;
import com.moneylog.core.ai.domain.AiFacts;
import com.moneylog.core.ai.domain.AiPreview;
import com.moneylog.core.ai.domain.AiPurpose;
import com.moneylog.core.ai.domain.AiSettings;
import com.moneylog.core.ai.domain.AiStatus;
import com.moneylog.core.ai.domain.InsightEngine;
import com.moneylog.core.ai.domain.Redactor;
import com.moneylog.core.ai.exception.AiExceptions.AiAnalysisNotFoundException;
import com.moneylog.core.ai.exception.AiExceptions.AiBudgetExceededException;
import com.moneylog.core.ai.exception.AiExceptions.AiDisabledException;
import com.moneylog.core.ai.exception.AiExceptions.AiPreviewAlreadyUsedException;
import com.moneylog.core.ai.exception.AiExceptions.AiPreviewExpiredException;
import com.moneylog.core.ai.exception.AiExceptions.AiPreviewMismatchException;
import com.moneylog.core.ai.exception.AiExceptions.AiPreviewNotFoundException;
import com.moneylog.core.ai.exception.AiExceptions.AiPurposeNotSupportedException;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.EntryLens;
import com.moneylog.core.finance.domain.EntryReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.MonthlySummary;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.TimeProvider;

/** Implementações dos casos de uso da IA. */
public final class AiUseCasesImpl {

    /** Teto de tokens da resposta; entra na estimativa da prévia e no pedido ao provedor. */
    public static final int MAX_OUTPUT_TOKENS = 600;
    private static final int SYSTEM_PROMPT_TOKENS = 250;
    private static final List<String> LANGUAGES = List.of("pt", "en", "es");

    private AiUseCasesImpl() {
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ insights (sem IA)

    public static class GetInsights implements GetInsightsUseCase {
        private final FinancialRecordsGateway records;
        private final CategoryGateway categories;
        private final TimeProvider time;

        public GetInsights(FinancialRecordsGateway records, CategoryGateway categories, TimeProvider time) {
            this.records = records;
            this.categories = categories;
            this.time = time;
        }

        @Override
        public InsightsView handle(UUID userId, Competence month) {
            FinancialRecords loaded = records.load(userId);
            var ledger = loaded.ledger();
            MonthlySummary summary = MonthlySummary.of(loaded, ledger, month, time.today());
            // consumo por categoria: o mesmo EntryReport da lente de consumo (nada de transferência, investimento ou empréstimo como despesa)
            var current = EntryReport.of(loaded, ledger, EntryLens.CONSUMPTION, month).byCategory();
            var previous = EntryReport.of(loaded, ledger, EntryLens.CONSUMPTION, month.minusMonths(1)).byCategory();
            Map<UUID, String> names = categories.list(userId, null, true).stream().collect(Collectors.toMap(Category::id, Category::name));
            var insights = InsightEngine.compute(current, previous, summary.card().outstanding(), id -> names.getOrDefault(id, ""));
            return new InsightsView(month, summary, insights, names);
        }
    }

    // ------------------------------------------------------------------ status

    public static class GetAiStatus implements GetAiStatusUseCase {
        private final AIService ai;
        private final AiGuard guard;

        public GetAiStatus(AIService ai, AiGuard guard) {
            this.ai = ai;
            this.guard = guard;
        }

        @Override
        public StatusView handle(UUID userId) {
            AiGuard.Usage usage = guard.usage(userId);
            return new StatusView(ai.enabled(), ai.provider(), ai.model(), usage.dailyCallsRemaining(), usage.monthlyTokensRemaining(),
                    usage.circuit());
        }
    }

    // ------------------------------------------------------------------ prévia

    public static class CreateAiPreview implements CreateAiPreviewUseCase {
        private final GetInsightsUseCase insights;
        private final AiPreviewGateway previews;
        private final IdGenerator ids;
        private final TimeProvider time;

        public CreateAiPreview(GetInsightsUseCase insights, AiPreviewGateway previews, IdGenerator ids, TimeProvider time) {
            this.insights = insights;
            this.previews = previews;
            this.ids = ids;
            this.time = time;
        }

        @Override
        public AiPreview handle(CreatePreviewCommand c) {
            if (!c.purpose().supported()) {
                throw new AiPurposeNotSupportedException(c.purpose().name());
            }
            String language = c.language() == null || !LANGUAGES.contains(c.language()) ? "pt" : c.language();
            Competence month = c.month() == null ? Competence.from(time.today()) : c.month();
            InsightsView view = insights.handle(c.userId(), month);
            AiFactsAssembler.Assembled assembled = AiFactsAssembler.assemble(view, new Redactor(List.of()));
            AiFacts facts = assembled.facts();
            String payload = facts.toPayload(c.purpose(), language);
            Instant now = time.now();
            int estimated = payload.length() / 3 + SYSTEM_PROMPT_TOKENS + MAX_OUTPUT_TOKENS;
            AiPreview preview = new AiPreview(ids.next(), c.userId(), c.purpose(), month.toString(), language, payload, sha256(payload), facts,
                    Map.of("facts", facts.size(), "insights", view.insights().size()), AiFactsAssembler.sorted(assembled.redactions()), estimated,
                    now, now.plus(Duration.ofMinutes(AiPreview.TTL_MINUTES)), null);
            return previews.save(preview);
        }
    }

    public static class GetAiPreview implements GetAiPreviewUseCase {
        private final AiPreviewGateway previews;

        public GetAiPreview(AiPreviewGateway previews) {
            this.previews = previews;
        }

        @Override
        public AiPreview handle(UUID userId, UUID previewId) {
            return previews.findById(userId, previewId).orElseThrow(() -> new AiPreviewNotFoundException(previewId));
        }
    }

    // ------------------------------------------------------------------ envio aprovado

    /**
     * Sem transação própria: cada chamada de gateway é uma transação curta, e o provedor é chamado fora de qualquer transação. Ordem:
     * idempotência, prévia (dono, validade, uso único), negação, IA ligada, hash, guarda (limite, orçamento, disjuntor), reivindica a prévia,
     * chama, valida (feito pelo {@link AIService}) e registra. A IA não escreve em nada além do próprio registro.
     */
    public static class RunApprovedAnalysis implements RunApprovedAnalysisUseCase {
        private final AIService ai;
        private final AiGuard guard;
        private final AiPreviewGateway previews;
        private final AiAnalysisGateway analyses;
        private final IdGenerator ids;
        private final TimeProvider time;

        public RunApprovedAnalysis(AIService ai, AiGuard guard, AiPreviewGateway previews, AiAnalysisGateway analyses, IdGenerator ids,
                                   TimeProvider time) {
            this.ai = ai;
            this.guard = guard;
            this.previews = previews;
            this.analyses = analyses;
            this.ids = ids;
            this.time = time;
        }

        @Override
        public AiAnalysis handle(RunAnalysisCommand c) {
            var existing = analyses.findByIdempotencyKey(c.userId(), c.idempotencyKey());
            if (existing.isPresent()) {
                return existing.get();
            }
            AiPreview preview = previews.findById(c.userId(), c.previewId()).orElseThrow(() -> new AiPreviewNotFoundException(c.previewId()));
            Instant start = time.now();
            if (preview.used()) {
                throw new AiPreviewAlreadyUsedException();
            }
            if (preview.expiredAt(start)) {
                throw new AiPreviewExpiredException();
            }
            if (!c.approve()) {
                previews.markUsed(c.userId(), preview.id(), start);
                return analyses.save(record(c, preview, AiStatus.DENIED, null, 0, 0, 0, null, "USER_DENIED", start));
            }
            if (!ai.enabled()) {
                throw new AiDisabledException();
            }
            if (!sha256(preview.payload()).equals(preview.payloadHash())
                    || !preview.payload().equals(preview.facts().toPayload(preview.purpose(), preview.language()))) {
                throw new AiPreviewMismatchException();
            }
            try {
                guard.acquire(c.userId(), preview.estimatedTokens());
            } catch (AiBudgetExceededException e) {
                analyses.save(record(c, preview, AiStatus.BUDGET_EXCEEDED, null, 0, 0, 0, null, "BUDGET_EXCEEDED", start));
                throw e;
            }
            AiResult result;
            boolean healthy = false;
            try {
                if (!previews.markUsed(c.userId(), preview.id(), start)) {
                    healthy = true;
                    throw new AiPreviewAlreadyUsedException();
                }
                result = call(preview);
                healthy = !(result instanceof AiUnavailable u && u.reason() == Reason.PROVIDER_ERROR);
            } finally {
                guard.release(healthy);
            }
            long duration = Duration.between(start, time.now()).toMillis();
            AiAnalysis analysis = switch (result) {
                case AiCompleted done -> record(c, preview, AiStatus.COMPLETED, preview.payload(), done.inputTokens(), done.outputTokens(), duration,
                        done, null, start);
                case AiUnavailable un -> record(c, preview, un.reason() == Reason.UNGROUNDED ? AiStatus.REJECTED_UNGROUNDED : AiStatus.FAILED,
                        preview.payload(), un.inputTokens(), un.outputTokens(), duration, null, un.reason().name(), start);
            };
            return analyses.save(analysis);
        }

        private AiResult call(AiPreview preview) {
            AiFacts facts = factsOf(preview);
            AiRequest request = new AiRequest(preview.userId(), preview.purpose(), preview.language(), preview.payload(), facts);
            return switch (preview.purpose()) {
                case MONTHLY_SUMMARY -> ai.generateFinancialSummary(request);
                case INSIGHTS -> ai.generateInsights(request);
                case ANALYZE_STATEMENT -> ai.analyzeStatement(request);
                case CATEGORIZE_IMPORT, CATEGORIZE_TRANSACTIONS -> ai.categorizeTransaction(request);
                case FORECAST -> ai.generateForecast(request);
                case DAILY_MESSAGE -> ai.generateDailyMessage(request);
            };
        }

        /** Os fatos vêm do próprio payload aprovado (a única coisa que o modelo viu), nunca de uma nova leitura do banco. */
        private static AiFacts factsOf(AiPreview preview) {
            return preview.facts();
        }

        private AiAnalysis record(RunAnalysisCommand c, AiPreview p, AiStatus status, String payload, int in, int out, long durationMs,
                                  AiCompleted done, String failureReason, Instant createdAt) {
            return new AiAnalysis(ids.next(), c.userId(), p.id(), c.idempotencyKey(), p.purpose(), p.period(), ai.provider(), ai.model(), status,
                    payload, p.payloadHash(), in, out, durationMs, done == null ? null : done.text(), done == null ? null : done.caveat(),
                    done == null ? List.of() : done.factRefs(), failureReason, createdAt, null);
        }
    }

    // ------------------------------------------------------------------ histórico

    public static class ListAiAnalyses implements ListAiAnalysesUseCase {
        private final AiAnalysisGateway analyses;

        public ListAiAnalyses(AiAnalysisGateway analyses) {
            this.analyses = analyses;
        }

        @Override
        public Slice<AiAnalysis> handle(UUID userId, AiPurpose purpose, AiStatus status, PageQuery page) {
            return analyses.list(userId, purpose, status, page);
        }
    }

    public static class GetAiAnalysis implements GetAiAnalysisUseCase {
        private final AiAnalysisGateway analyses;

        public GetAiAnalysis(AiAnalysisGateway analyses) {
            this.analyses = analyses;
        }

        @Override
        public AiAnalysis handle(UUID userId, UUID id) {
            return analyses.findById(userId, id).orElseThrow(() -> new AiAnalysisNotFoundException(id));
        }
    }

    // ------------------------------------------------------------------ configurações

    public static class GetAiSettings implements GetAiSettingsUseCase {
        private final AiSettingsGateway settings;

        public GetAiSettings(AiSettingsGateway settings) {
            this.settings = settings;
        }

        @Override
        public AiSettings handle(UUID userId) {
            return settings.find(userId).orElse(AiSettings.defaults(userId));
        }
    }

    public static class SaveAiSettings implements SaveAiSettingsUseCase {
        private final AiSettingsGateway settings;
        private final AIService ai;

        public SaveAiSettings(AiSettingsGateway settings, AIService ai) {
            this.settings = settings;
            this.ai = ai;
        }

        @Override
        public AiSettings handle(UUID userId, boolean dailyMessageEnabled) {
            if (dailyMessageEnabled && !ai.enabled()) {
                throw new AiDisabledException();
            }
            return settings.save(new AiSettings(userId, dailyMessageEnabled));
        }
    }

    // ------------------------------------------------------------------ retenção

    public static class PurgeExpiredAiData implements PurgeExpiredAiDataUseCase {
        private final AiAnalysisGateway analyses;
        private final AiPreviewGateway previews;
        private final TimeProvider time;
        private final int retentionDays;

        public PurgeExpiredAiData(AiAnalysisGateway analyses, AiPreviewGateway previews, TimeProvider time, int retentionDays) {
            this.analyses = analyses;
            this.previews = previews;
            this.time = time;
            this.retentionDays = retentionDays;
        }

        @Override
        public int handle() {
            Instant now = time.now();
            previews.deleteExpiredBefore(now.minus(Duration.ofDays(1)));
            return analyses.purgePayloadsBefore(now.minus(Duration.ofDays(retentionDays)), now);
        }
    }
}
