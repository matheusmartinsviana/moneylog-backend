package com.moneylog.core.ai.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.moneylog.core.ai.app.AiResult.AiCompleted;
import com.moneylog.core.ai.app.AiResult.AiUnavailable;
import com.moneylog.core.ai.app.AiResult.Reason;
import com.moneylog.core.ai.app.gateways.AiAnalysisGateway;
import com.moneylog.core.ai.app.gateways.AiPreviewGateway;
import com.moneylog.core.ai.app.usecase.AiUseCases.RunAnalysisCommand;
import com.moneylog.core.ai.app.usecase.AiUseCasesImpl.RunApprovedAnalysis;
import com.moneylog.core.ai.domain.AiAnalysis;
import com.moneylog.core.ai.domain.AiFacts;
import com.moneylog.core.ai.domain.AiPreview;
import com.moneylog.core.ai.domain.AiPurpose;
import com.moneylog.core.ai.domain.AiStatus;
import com.moneylog.core.ai.exception.AiExceptions.AiBudgetExceededException;
import com.moneylog.core.ai.exception.AiExceptions.AiDisabledException;
import com.moneylog.core.ai.exception.AiExceptions.AiPreviewAlreadyUsedException;
import com.moneylog.core.ai.exception.AiExceptions.AiPreviewExpiredException;
import com.moneylog.core.ai.exception.AiExceptions.AiPreviewMismatchException;
import com.moneylog.core.ai.exception.AiExceptions.AiPreviewNotFoundException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.ClockTimeProvider;
import com.moneylog.util.MutableClock;

class RunApprovedAnalysisTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();

    private final AIService ai = mock(AIService.class);
    private final AiGuard guard = mock(AiGuard.class);
    private final AiPreviewGateway previews = mock(AiPreviewGateway.class);
    private final AiAnalysisGateway analyses = mock(AiAnalysisGateway.class);
    private final IdGenerator ids = UUID::randomUUID;
    private MutableClock clock;
    private RunApprovedAnalysis useCase;
    private AiPreview preview;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-07T15:00:00Z"));
        useCase = new RunApprovedAnalysis(ai, guard, previews, analyses, ids, new ClockTimeProvider(clock, ZoneId.of("America/Sao_Paulo")));
        preview = preview(Map.of("month", "2026-10", "partial", "false", "income", "3000.00"));
        when(previews.findById(USER, preview.id())).thenReturn(Optional.of(preview));
        when(previews.markUsed(any(), any(), any())).thenReturn(true);
        when(ai.enabled()).thenReturn(true);
        when(ai.provider()).thenReturn("fake");
        when(ai.model()).thenReturn("m");
        when(analyses.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private AiPreview preview(Map<String, String> values) {
        AiFacts facts = new AiFacts(values);
        String payload = facts.toPayload(AiPurpose.MONTHLY_SUMMARY, "pt");
        return new AiPreview(UUID.randomUUID(), USER, AiPurpose.MONTHLY_SUMMARY, "2026-10", "pt", payload, hash(payload), facts, Map.of("facts", values.size()),
                List.of(), 900, clock == null ? Instant.now() : clock.instant(), Instant.parse("2026-10-07T15:15:00Z"), null);
    }

    private static String hash(String payload) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private RunAnalysisCommand approve() {
        return new RunAnalysisCommand(USER, preview.id(), true, KEY);
    }

    @Test
    @DisplayName("aprovado: envia exatamente o payload da prévia e registra COMPLETED com o payload e os tokens")
    void deveEnviarOPayloadAprovadoERegistrar() {
        when(ai.generateFinancialSummary(any())).thenReturn(new AiCompleted("Receitas de R$ 3.000,00.", null, List.of("income"), 120, 30));

        AiAnalysis result = useCase.handle(approve());

        ArgumentCaptor<AiRequest> sent = ArgumentCaptor.forClass(AiRequest.class);
        verify(ai).generateFinancialSummary(sent.capture());
        assertThat(sent.getValue().payload()).isEqualTo(preview.payload());
        assertThat(result.status()).isEqualTo(AiStatus.COMPLETED);
        assertThat(result.redactedPayload()).isEqualTo(preview.payload());
        assertThat(result.totalTokens()).isEqualTo(150);
        assertThat(result.resultText()).contains("3.000,00");
        verify(guard).acquire(USER, 900);
        verify(guard).release(true);
        verify(previews).markUsed(USER, preview.id(), clock.instant());
    }

    @Test
    @DisplayName("sem approve: registra DENIED sem payload e NADA é enviado")
    void naoDeveEnviarQuandoNegado() {
        AiAnalysis result = useCase.handle(new RunAnalysisCommand(USER, preview.id(), false, KEY));

        assertThat(result.status()).isEqualTo(AiStatus.DENIED);
        assertThat(result.redactedPayload()).isNull();
        verifyNoInteractions(guard);
        verify(ai, never()).generateFinancialSummary(any());
    }

    @Test
    @DisplayName("IA desligada: 422 AI_DISABLED, sem registro de envio e sem consumir a prévia")
    void deveRecusarComIaDesligada() {
        when(ai.enabled()).thenReturn(false);

        assertThatThrownBy(() -> useCase.handle(approve())).isInstanceOf(AiDisabledException.class);

        verify(analyses, never()).save(any());
        verify(previews, never()).markUsed(any(), any(), any());
    }

    @Test
    void deveRecusarPreviaExpiradaEPreviaDeOutroUsuario() {
        clock.set(Instant.parse("2026-10-07T15:15:00Z"));
        assertThatThrownBy(() -> useCase.handle(approve())).isInstanceOf(AiPreviewExpiredException.class);

        UUID other = UUID.randomUUID();
        when(previews.findById(other, preview.id())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.handle(new RunAnalysisCommand(other, preview.id(), true, KEY))).isInstanceOf(AiPreviewNotFoundException.class);
        verify(ai, never()).generateFinancialSummary(any());
    }

    @Test
    @DisplayName("payload adulterado (hash diferente): AI_PREVIEW_MISMATCH e nada enviado")
    void deveBloquearPayloadAlterado() {
        AiPreview tampered = new AiPreview(preview.id(), USER, preview.purpose(), preview.period(), "pt", preview.payload().replace("3000.00", "9999.00"),
                preview.payloadHash(), preview.facts(), preview.counts(), preview.redactions(), 900, preview.createdAt(), preview.expiresAt(), null);
        when(previews.findById(USER, preview.id())).thenReturn(Optional.of(tampered));

        assertThatThrownBy(() -> useCase.handle(approve())).isInstanceOf(AiPreviewMismatchException.class);

        verify(ai, never()).generateFinancialSummary(any());
        verifyNoInteractions(guard);
    }

    @Test
    @DisplayName("prévia já usada: não envia de novo")
    void naoDeveReusarPrevia() {
        when(previews.findById(USER, preview.id())).thenReturn(Optional.of(new AiPreview(preview.id(), USER, preview.purpose(), preview.period(), "pt",
                preview.payload(), preview.payloadHash(), preview.facts(), preview.counts(), preview.redactions(), 900, preview.createdAt(),
                preview.expiresAt(), Instant.parse("2026-10-07T15:01:00Z"))));

        assertThatThrownBy(() -> useCase.handle(approve())).isInstanceOf(AiPreviewAlreadyUsedException.class);
        verify(ai, never()).generateFinancialSummary(any());
    }

    @Test
    @DisplayName("corrida pela prévia: quem perde a reivindicação não chama o provedor e devolve a reserva")
    void naoDeveChamarQuandoPerdeAReivindicacao() {
        when(previews.markUsed(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> useCase.handle(approve())).isInstanceOf(AiPreviewAlreadyUsedException.class);

        verify(ai, never()).generateFinancialSummary(any());
        verify(guard).release(true);
    }

    @Test
    @DisplayName("Idempotency-Key repetida devolve a mesma análise sem nova chamada")
    void deveSerIdempotente() {
        AiAnalysis previous = new AiAnalysis(UUID.randomUUID(), USER, preview.id(), KEY, AiPurpose.MONTHLY_SUMMARY, "2026-10", "fake", "m",
                AiStatus.COMPLETED, preview.payload(), preview.payloadHash(), 1, 1, 1, "t", null, List.of(), null, clock.instant(), null);
        when(analyses.findByIdempotencyKey(USER, KEY)).thenReturn(Optional.of(previous));

        assertThat(useCase.handle(approve())).isSameAs(previous);

        verifyNoInteractions(guard);
        verify(ai, never()).generateFinancialSummary(any());
    }

    @Test
    @DisplayName("orçamento estourado: registra BUDGET_EXCEEDED sem payload e lança 422")
    void deveRegistrarOrcamentoEstourado() {
        org.mockito.Mockito.doThrow(new AiBudgetExceededException()).when(guard).acquire(USER, 900);

        assertThatThrownBy(() -> useCase.handle(approve())).isInstanceOf(AiBudgetExceededException.class);

        ArgumentCaptor<AiAnalysis> saved = ArgumentCaptor.forClass(AiAnalysis.class);
        verify(analyses).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(AiStatus.BUDGET_EXCEEDED);
        assertThat(saved.getValue().redactedPayload()).isNull();
        verify(ai, never()).generateFinancialSummary(any());
    }

    @Test
    @DisplayName("número inventado: REJECTED_UNGROUNDED, sem texto da IA e fallback determinístico")
    void deveRegistrarRespostaSemAterramento() {
        when(ai.generateFinancialSummary(any())).thenReturn(new AiUnavailable(Reason.UNGROUNDED, 100, 20));

        AiAnalysis result = useCase.handle(approve());

        assertThat(result.status()).isEqualTo(AiStatus.REJECTED_UNGROUNDED);
        assertThat(result.resultText()).isNull();
        assertThat(result.hasResult()).isFalse();
        verify(guard).release(true);
    }

    @Test
    @DisplayName("falha do provedor: FAILED e o disjuntor é avisado")
    void deveRegistrarFalhaDoProvedor() {
        when(ai.generateFinancialSummary(any())).thenReturn(new AiUnavailable(Reason.PROVIDER_ERROR));

        AiAnalysis result = useCase.handle(approve());

        assertThat(result.status()).isEqualTo(AiStatus.FAILED);
        assertThat(result.failureReason()).isEqualTo("PROVIDER_ERROR");
        verify(guard).release(false);
    }
}
