package com.moneylog.core.ai.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.ai.app.gateways.AiAnalysisGateway;
import com.moneylog.core.ai.exception.AiExceptions.AiBudgetExceededException;
import com.moneylog.core.ai.exception.AiExceptions.AiProviderUnavailableException;
import com.moneylog.core.ai.exception.AiExceptions.AiRateLimitedException;
import com.moneylog.core.sk.time.ClockTimeProvider;
import com.moneylog.util.MutableClock;

class AiGuardTest {

    private static final UUID USER = UUID.randomUUID();
    private final AiAnalysisGateway analyses = mock(AiAnalysisGateway.class);
    private MutableClock clock;
    private ClockTimeProvider time;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-07T15:00:00Z"));
        time = new ClockTimeProvider(clock, ZoneId.of("America/Sao_Paulo"));
    }

    private AiGuard guard(int daily, long budget) {
        return new AiGuard(analyses, time, new AiGuard.Limits(daily, budget));
    }

    @Test
    @DisplayName("a chamada além do limite diário vira 429 sem ser reservada")
    void deveBloquearAlemDoLimiteDiario() {
        when(analyses.countProviderCalls(eq(USER), any(), any())).thenReturn(3L);
        assertThatThrownBy(() -> guard(3, 1_000_000).acquire(USER, 10)).isInstanceOf(AiRateLimitedException.class);
    }

    @Test
    @DisplayName("o dia vai de meia-noite a meia-noite em America/Sao_Paulo")
    void deveUsarODiaDeSaoPaulo() {
        AiGuard guard = guard(3, 1_000_000);
        guard.usage(USER);
        org.mockito.Mockito.verify(analyses).countProviderCalls(USER, Instant.parse("2026-10-07T03:00:00Z"), Instant.parse("2026-10-08T03:00:00Z"));
    }

    @Test
    @DisplayName("prévia estimada acima do orçamento restante: 422 antes de enviar")
    void deveBloquearOrcamentoEstourado() {
        when(analyses.sumTokens(eq(USER), any(), any())).thenReturn(0L);
        assertThatThrownBy(() -> guard(50, 1000).acquire(USER, 1200)).isInstanceOf(AiBudgetExceededException.class);
        when(analyses.sumTokens(eq(USER), any(), any())).thenReturn(900L);
        assertThatThrownBy(() -> guard(50, 1000).acquire(USER, 200)).isInstanceOf(AiBudgetExceededException.class);
        guard(50, 1000).acquire(USER, 100);
    }

    @Test
    @DisplayName("5 falhas seguidas abrem o disjuntor por 5 min; depois a sondagem bem-sucedida fecha")
    void deveAbrirEFecharODisjuntor() {
        AiGuard guard = guard(50, 1_000_000);
        for (int i = 0; i < AiGuard.FAILURES_TO_OPEN; i++) {
            guard.acquire(USER, 10);
            guard.release(false);
        }
        assertThat(guard.usage(USER).circuit()).isEqualTo(AiGuard.Circuit.OPEN);
        assertThatThrownBy(() -> guard.acquire(USER, 10)).isInstanceOf(AiProviderUnavailableException.class);

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertThat(guard.usage(USER).circuit()).isEqualTo(AiGuard.Circuit.CLOSED);
        guard.acquire(USER, 10);
        guard.release(true);
        // uma falha depois da sondagem bem-sucedida não reabre
        guard.acquire(USER, 10);
        guard.release(false);
        assertThat(guard.usage(USER).circuit()).isEqualTo(AiGuard.Circuit.CLOSED);
    }

    @Test
    @DisplayName("falha da sondagem reabre na hora")
    void deveReabrirQuandoASondagemFalha() {
        AiGuard guard = guard(50, 1_000_000);
        for (int i = 0; i < AiGuard.FAILURES_TO_OPEN; i++) {
            guard.acquire(USER, 10);
            guard.release(false);
        }
        clock.advance(Duration.ofMinutes(6));
        guard.acquire(USER, 10);
        guard.release(false);
        assertThat(guard.usage(USER).circuit()).isEqualTo(AiGuard.Circuit.OPEN);
    }

    @Test
    @DisplayName("10 chamadas simultâneas num limite de 3: só 3 passam")
    void naoDeveUltrapassarOLimiteComConcorrencia() throws Exception {
        AiGuard guard = guard(3, 1_000_000);
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        for (int i = 0; i < 10; i++) {
            pool.submit(() -> {
                try {
                    guard.acquire(USER, 10);
                    allowed.incrementAndGet();
                } catch (AiRateLimitedException expected) {
                    // bloqueada
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(allowed.get()).isEqualTo(3);
    }
}
