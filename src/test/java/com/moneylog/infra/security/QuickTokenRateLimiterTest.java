package com.moneylog.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.util.MutableClock;

@DisplayName("Limitadores do token de lançamento rápido")
class QuickTokenRateLimiterTest {

    private static final Instant START = Instant.parse("2026-10-21T12:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final QuickTokenRateLimiter limiter = new QuickTokenRateLimiter(clock,
            new QuickTokenRateLimiter.Settings(3, Duration.ofMinutes(5), 2, Duration.ofMinutes(1), 4));

    @Test
    @DisplayName("bloqueia o IP depois de N falhas na janela, com o tempo de espera, e libera quando a janela passa")
    void deveBloquearIpPorFalhas() {
        assertThat(limiter.recordFailure("203.0.113.9")).isFalse();
        assertThat(limiter.recordFailure("203.0.113.9")).isFalse();
        limiter.ensureIpAllowed("203.0.113.9");
        assertThat(limiter.recordFailure("203.0.113.9")).isTrue();

        clock.advance(Duration.ofMinutes(1));
        assertThatThrownBy(() -> limiter.ensureIpAllowed("203.0.113.9")).isInstanceOfSatisfying(TooManyAttemptsException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofMinutes(4)));
        assertThatCode(() -> limiter.ensureIpAllowed("203.0.113.10")).doesNotThrowAnyException();

        clock.advance(Duration.ofMinutes(4));
        assertThatCode(() -> limiter.ensureIpAllowed("203.0.113.9")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a rajada só é anunciada uma vez por janela, na falha que cruza o limite")
    void deveAnunciarRajadaUmaVez() {
        limiter.recordFailure("198.51.100.1");
        limiter.recordFailure("198.51.100.1");
        assertThat(limiter.recordFailure("198.51.100.1")).isTrue();
        assertThat(limiter.recordFailure("198.51.100.1")).isFalse();
    }

    @Test
    @DisplayName("IPv6 do mesmo /64 conta junto")
    void deveAgruparIpv6() {
        limiter.recordFailure("2001:db8:1:2::1");
        limiter.recordFailure("2001:db8:1:2::2");
        limiter.recordFailure("2001:db8:1:2:ffff::3");
        assertThatThrownBy(() -> limiter.ensureIpAllowed("2001:db8:1:2::99")).isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    @DisplayName("limita as chamadas por token por janela sem contar a chamada recusada, e outro token não é afetado")
    void deveLimitarChamadasPorToken() {
        UUID token = UUID.randomUUID();
        limiter.acquireCall(token);
        limiter.acquireCall(token);
        assertThatThrownBy(() -> limiter.acquireCall(token)).isInstanceOf(TooManyAttemptsException.class);
        assertThatCode(() -> limiter.acquireCall(UUID.randomUUID())).doesNotThrowAnyException();

        clock.advance(Duration.ofSeconds(61));
        assertThatCode(() -> limiter.acquireCall(token)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a memória é limitada: passando do teto, descarta o mais antigo")
    void deveLimitarAMemoria() {
        for (int i = 0; i < 20; i++) {
            limiter.recordFailure("10.0.0." + i);
            limiter.acquireCall(UUID.randomUUID());
        }
        assertThat(limiter.trackedEntries()).isLessThanOrEqualTo(8);
    }
}
