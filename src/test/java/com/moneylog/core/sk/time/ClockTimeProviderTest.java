package com.moneylog.core.sk.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ClockTimeProvider")
class ClockTimeProviderTest {

    @Test
    @DisplayName("hoje usa o fuso America/Sao_Paulo (S-1): 02:00 UTC de 11/10 ainda é 10/10 no Brasil")
    void deveUsarOFusoDoUsuario() {
        Instant instante = Instant.parse("2026-10-11T02:00:00.123456789Z");
        TimeProvider time = new ClockTimeProvider(Clock.fixed(instante, ZoneOffset.UTC));

        assertThat(time.zone()).isEqualTo(ClockTimeProvider.DEFAULT_ZONE);
        assertThat(time.today()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(time.now()).isEqualTo(Instant.parse("2026-10-11T02:00:00.123456Z"));
    }

    @Test
    @DisplayName("aceita outro fuso")
    void deveAceitarOutroFuso() {
        Instant instante = Instant.parse("2026-10-11T02:00:00Z");
        TimeProvider time = new ClockTimeProvider(Clock.fixed(instante, ZoneOffset.UTC), ZoneOffset.UTC);

        assertThat(time.today()).isEqualTo(LocalDate.of(2026, 10, 11));
    }
}
