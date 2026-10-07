package com.moneylog.infra.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.infra.export.controller.ExportRateLimiter;
import com.moneylog.infra.security.TooManyAttemptsException;

@DisplayName("ExportRateLimiter: limite de taxa em memória das exportações (S-260)")
class ExportRateLimiterTest {

    /** Relógio que avança sob controle do teste. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-10T15:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("deixa passar maxRequests na janela, recusa o seguinte com Retry-After até a janela do mais antigo vencer, e libera depois")
    void deveLimitarPorJanela() {
        TestClock clock = new TestClock();
        ExportRateLimiter limiter = new ExportRateLimiter(clock, 3, Duration.ofMinutes(1));
        UUID user = UUID.randomUUID();
        limiter.acquire(user);
        clock.advance(Duration.ofSeconds(10));
        limiter.acquire(user);
        limiter.acquire(user);

        assertThatThrownBy(() -> limiter.acquire(user)).isInstanceOfSatisfying(TooManyAttemptsException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(50)));
        clock.advance(Duration.ofSeconds(49));
        assertThatThrownBy(() -> limiter.acquire(user)).isInstanceOf(TooManyAttemptsException.class);
        clock.advance(Duration.ofSeconds(1)); // o 1º pedido (t=0) saiu da janela
        limiter.acquire(user);
    }

    @Test
    @DisplayName("o limite é por usuário: um usuário no limite não bloqueia outro")
    void deveSerPorUsuario() {
        ExportRateLimiter limiter = new ExportRateLimiter(new TestClock(), 1, Duration.ofMinutes(1));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        limiter.acquire(a);

        assertThatThrownBy(() -> limiter.acquire(a)).isInstanceOf(TooManyAttemptsException.class);
        limiter.acquire(b);
    }
}
