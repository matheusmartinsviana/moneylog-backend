package com.moneylog.core.ai.app.usecase;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.ai.app.AiGuard;
import com.moneylog.core.ai.domain.AiAnalysis;
import com.moneylog.core.ai.domain.AiPreview;
import com.moneylog.core.ai.domain.AiPurpose;
import com.moneylog.core.ai.domain.AiSettings;
import com.moneylog.core.ai.domain.AiStatus;
import com.moneylog.core.insights.domain.Insight;
import com.moneylog.core.finance.domain.MonthlySummary;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.time.Competence;

/** Casos de uso da IA (Etapa 8). Um por ação, {@code handle(...)}. */
public final class AiUseCases {

    private AiUseCases() {
    }

    /** Resumo do mês + insights determinísticos (sem IA) + nomes de categoria; a mesma fonte do dashboard e do resumo mensal. */
    public record InsightsView(Competence month, MonthlySummary summary, List<Insight> insights, Map<UUID, String> categoryNames) {
    }

    public record StatusView(boolean enabled, String provider, String model, long dailyCallsRemaining, long monthlyTokensRemaining,
                             AiGuard.Circuit circuit) {
    }

    public record CreatePreviewCommand(UUID userId, AiPurpose purpose, Competence month, String language) {
    }

    public record RunAnalysisCommand(UUID userId, UUID previewId, boolean approve, UUID idempotencyKey) {
    }

    public interface GetInsightsUseCase {
        InsightsView handle(UUID userId, Competence month);
    }

    public interface GetAiStatusUseCase {
        StatusView handle(UUID userId);
    }

    public interface CreateAiPreviewUseCase {
        AiPreview handle(CreatePreviewCommand command);
    }

    public interface GetAiPreviewUseCase {
        AiPreview handle(UUID userId, UUID previewId);
    }

    /** Envia à IA exatamente o payload da prévia aprovada (S-181). */
    public interface RunApprovedAnalysisUseCase {
        AiAnalysis handle(RunAnalysisCommand command);
    }

    public interface ListAiAnalysesUseCase {
        Slice<AiAnalysis> handle(UUID userId, AiPurpose purpose, AiStatus status, PageQuery page);
    }

    public interface GetAiAnalysisUseCase {
        AiAnalysis handle(UUID userId, UUID id);
    }

    public interface GetAiSettingsUseCase {
        AiSettings handle(UUID userId);
    }

    public interface SaveAiSettingsUseCase {
        AiSettings handle(UUID userId, boolean dailyMessageEnabled);
    }

    /** Retenção (S-183): apaga o payload enviado com mais de N dias e as prévias vencidas; o registro da análise permanece. */
    public interface PurgeExpiredAiDataUseCase {
        int handle();
    }
}
