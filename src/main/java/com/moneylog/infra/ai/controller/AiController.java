package com.moneylog.infra.ai.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.moneylog.core.ai.app.usecase.AiUseCases.CreateAiPreviewUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.CreatePreviewCommand;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetAiAnalysisUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetAiPreviewUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetAiSettingsUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetAiStatusUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetInsightsUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.ListAiAnalysesUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.RunAnalysisCommand;
import com.moneylog.core.ai.app.usecase.AiUseCases.RunApprovedAnalysisUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.SaveAiSettingsUseCase;
import com.moneylog.core.ai.domain.AiPurpose;
import com.moneylog.core.ai.domain.AiStatus;
import com.moneylog.core.ai.domain.Insight;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.ai.controller.AiDtos.AnalysisInput;
import com.moneylog.infra.ai.controller.AiDtos.AnalysisResponse;
import com.moneylog.infra.ai.controller.AiDtos.AnalysisSummaryResponse;
import com.moneylog.infra.ai.controller.AiDtos.InsightResponse;
import com.moneylog.infra.ai.controller.AiDtos.PreviewInput;
import com.moneylog.infra.ai.controller.AiDtos.PreviewResponse;
import com.moneylog.infra.ai.controller.AiDtos.SettingsDto;
import com.moneylog.infra.ai.controller.AiDtos.StatusResponse;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.QueryMonths;
import com.moneylog.infra.web.SliceResponse;

/**
 * IA (Etapa 8): status, prévia → aprovação → análise, histórico, insights determinísticos e preferências. Todo endpoint filtra pelo usuário do
 * token e nenhuma resposta é cacheada. Sem provedor configurado, tudo responde (status {@code enabled: false}, envio {@code 422 AI_DISABLED},
 * insights normais).
 */
@RestController
public class AiController {

    private static final CacheControl NO_STORE = CacheControl.noStore().cachePrivate();
    public static final String INSIGHTS_MONTH_INVALID = "INSIGHTS_MONTH_INVALID";

    private final GetAiStatusUseCase status;
    private final CreateAiPreviewUseCase createPreview;
    private final GetAiPreviewUseCase getPreview;
    private final RunApprovedAnalysisUseCase runAnalysis;
    private final ListAiAnalysesUseCase listAnalyses;
    private final GetAiAnalysisUseCase getAnalysis;
    private final GetInsightsUseCase insights;
    private final GetAiSettingsUseCase getSettings;
    private final SaveAiSettingsUseCase saveSettings;
    private final TimeProvider time;

    @SuppressWarnings("java:S107")
    public AiController(GetAiStatusUseCase status, CreateAiPreviewUseCase createPreview, GetAiPreviewUseCase getPreview,
                        RunApprovedAnalysisUseCase runAnalysis, ListAiAnalysesUseCase listAnalyses, GetAiAnalysisUseCase getAnalysis,
                        GetInsightsUseCase insights, GetAiSettingsUseCase getSettings, SaveAiSettingsUseCase saveSettings, TimeProvider time) {
        this.status = status;
        this.createPreview = createPreview;
        this.getPreview = getPreview;
        this.runAnalysis = runAnalysis;
        this.listAnalyses = listAnalyses;
        this.getAnalysis = getAnalysis;
        this.insights = insights;
        this.getSettings = getSettings;
        this.saveSettings = saveSettings;
        this.time = time;
    }

    @GetMapping("/v1/ai/status")
    public ResponseEntity<StatusResponse> status(@CurrentUser UUID userId) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(StatusResponse.of(status.handle(userId)));
    }

    @PostMapping("/v1/ai/previews")
    public ResponseEntity<PreviewResponse> createPreview(@CurrentUser UUID userId, @Valid @RequestBody PreviewInput body) {
        Competence month = body.scope() == null || body.scope().month() == null ? null
                : QueryMonths.parse(body.scope().month(), "AI_SCOPE_MONTH_INVALID", "scope.month");
        String language = body.scope() == null ? null : body.scope().language();
        PreviewResponse created = PreviewResponse.of(createPreview.handle(new CreatePreviewCommand(userId, body.purpose(), month, language)));
        return ResponseEntity.created(URI.create("/v1/ai/previews/" + created.previewId())).cacheControl(NO_STORE).body(created);
    }

    @GetMapping("/v1/ai/previews/{id}")
    public ResponseEntity<PreviewResponse> preview(@CurrentUser UUID userId, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(PreviewResponse.of(getPreview.handle(userId, id)));
    }

    @PostMapping("/v1/ai/analyses")
    public ResponseEntity<AnalysisResponse> analyze(@CurrentUser UUID userId, @RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                                    @Valid @RequestBody AnalysisInput body) {
        AnalysisResponse created = AnalysisResponse.of(runAnalysis.handle(new RunAnalysisCommand(userId, body.previewId(), body.approve(),
                idempotencyKey)));
        return ResponseEntity.created(URI.create("/v1/ai/analyses/" + created.id())).cacheControl(NO_STORE).body(created);
    }

    @GetMapping("/v1/ai/analyses")
    public ResponseEntity<SliceResponse<AnalysisSummaryResponse>> analyses(@CurrentUser UUID userId,
                                                                           @RequestParam(required = false) AiPurpose purpose,
                                                                           @RequestParam(required = false) AiStatus status, PageQuery page) {
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .body(SliceResponse.of(listAnalyses.handle(userId, purpose, status, page), AnalysisSummaryResponse::of));
    }

    @GetMapping("/v1/ai/analyses/{id}")
    public ResponseEntity<AnalysisResponse> analysis(@CurrentUser UUID userId, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(AnalysisResponse.of(getAnalysis.handle(userId, id)));
    }

    /** Insights determinísticos do mês (padrão: o corrente): valem sem IA. */
    @GetMapping("/v1/insights")
    public ResponseEntity<SliceResponse<InsightResponse>> insights(@CurrentUser UUID userId, @RequestParam(required = false) String month,
                                                                   PageQuery page) {
        Competence competence = month == null ? Competence.from(time.today()) : QueryMonths.parse(month, INSIGHTS_MONTH_INVALID, "month");
        List<Insight> all = insights.handle(userId, competence).insights();
        int from = (int) Math.min(page.offset(), all.size());
        int to = Math.min(all.size(), from + page.pageSize());
        Slice<Insight> slice = new Slice<>(all.subList(from, to), to < all.size());
        return ResponseEntity.ok().cacheControl(NO_STORE).body(SliceResponse.of(slice, InsightResponse::of));
    }

    @GetMapping("/v1/settings/ai")
    public ResponseEntity<SettingsDto> settings(@CurrentUser UUID userId) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(SettingsDto.of(getSettings.handle(userId)));
    }

    @PutMapping("/v1/settings/ai")
    public ResponseEntity<SettingsDto> saveSettings(@CurrentUser UUID userId, @Valid @RequestBody SettingsDto body) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(SettingsDto.of(saveSettings.handle(userId, body.dailyMessageEnabled())));
    }
}
