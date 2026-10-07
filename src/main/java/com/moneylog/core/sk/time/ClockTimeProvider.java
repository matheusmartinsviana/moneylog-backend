package com.moneylog.core.sk.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Implementação do {@link TimeProvider} sobre {@link Clock} (Java puro). Em produção recebe {@code Clock.systemUTC()};
 * nos testes, {@code Clock.fixed(...)}.
 */
public final class ClockTimeProvider implements TimeProvider {

    /** Fuso padrão do usuário (S-1). */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Sao_Paulo");

    private final Clock clock;
    private final ZoneId zone;

    public ClockTimeProvider(Clock clock, ZoneId zone) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public ClockTimeProvider(Clock clock) {
        this(clock, DEFAULT_ZONE);
    }

    /** Instante em UTC, sem frações abaixo de microssegundo (compatível com {@code TIMESTAMPTZ} do Postgres). */
    @Override
    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    @Override
    public ZoneId zone() {
        return zone;
    }
}
