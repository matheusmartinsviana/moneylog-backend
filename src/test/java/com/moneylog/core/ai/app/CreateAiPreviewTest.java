package com.moneylog.core.ai.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.moneylog.core.ai.app.gateways.AiPreviewGateway;
import com.moneylog.core.ai.app.usecase.AiUseCases.CreatePreviewCommand;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetInsightsUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.InsightsView;
import com.moneylog.core.ai.app.usecase.AiUseCasesImpl.CreateAiPreview;
import com.moneylog.core.ai.domain.AiPreview;
import com.moneylog.core.ai.domain.AiPurpose;
import com.moneylog.core.insights.domain.InsightEngine;
import com.moneylog.core.ai.exception.AiExceptions.AiPurposeNotSupportedException;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.MonthlySummary;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.ClockTimeProvider;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.util.MutableClock;

class CreateAiPreviewTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID FOOD = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final GetInsightsUseCase insights = mock(GetInsightsUseCase.class);
    private final AiPreviewGateway previews = mock(AiPreviewGateway.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T15:00:00Z"));
    private final CreateAiPreview useCase = new CreateAiPreview(insights, previews, UUID::randomUUID,
            new ClockTimeProvider(clock, ZoneId.of("America/Sao_Paulo")));

    @Test
    @DisplayName("a prévia traz só agregados: sem ids, descrições, e-mails; payload e hash coerentes; vale 15 min")
    void deveMontarPreviaMinimizada() {
        Competence month = Competence.parse("2026-10");
        FinancialRecords empty = new FinancialRecords(List.of(), List.of(), List.of(), List.of());
        MonthlySummary summary = MonthlySummary.of(empty, empty.ledger(), month, clock.instant().atZone(ZoneId.of("America/Sao_Paulo")).toLocalDate());
        var found = InsightEngine.compute(Map.of(FOOD, Money.of("684.50")), Map.of(FOOD, Money.of("500.00")), Money.of("2400.00"),
                id -> "Alimentação PIX Fulano");
        when(insights.handle(USER, month)).thenReturn(new InsightsView(month, summary, found, Map.of(FOOD, "Alimentação")));
        when(previews.save(any())).thenAnswer(i -> i.getArgument(0));

        AiPreview preview = useCase.handle(new CreatePreviewCommand(USER, AiPurpose.MONTHLY_SUMMARY, month, "pt"));

        assertThat(preview.payload()).doesNotContain(FOOD.toString()).doesNotContain(USER.toString()).doesNotContain("@")
                .contains("\"purpose\":\"MONTHLY_SUMMARY\"").contains("\"partial\":\"true\"").contains("\"insight.1.deltaAmount\":\"184.50\"");
        assertThat(preview.payload()).contains("[PIX]").doesNotContain("FULANO");
        assertThat(preview.redactions()).contains("PAYEE");
        assertThat(preview.payloadHash()).hasSize(64);
        assertThat(preview.payload()).isEqualTo(preview.facts().toPayload(AiPurpose.MONTHLY_SUMMARY, "pt"));
        assertThat(preview.estimatedTokens()).isGreaterThan(600);
        assertThat(preview.counts()).containsEntry("insights", 2);
        assertThat(preview.expiresAt()).isEqualTo(clock.instant().plusSeconds(15 * 60));
        ArgumentCaptor<AiPreview> saved = ArgumentCaptor.forClass(AiPreview.class);
        verify(previews).save(saved.capture());
        assertThat(saved.getValue().userId()).isEqualTo(USER);
    }

    @Test
    @DisplayName("finalidade ainda sem fatos montados: 422 AI_PURPOSE_NOT_SUPPORTED e nada gravado")
    void deveRecusarFinalidadeNaoSuportada() {
        assertThatThrownBy(() -> useCase.handle(new CreatePreviewCommand(USER, AiPurpose.CATEGORIZE_IMPORT, null, "pt")))
                .isInstanceOf(AiPurposeNotSupportedException.class);
        verify(previews, never()).save(any());
    }
}
