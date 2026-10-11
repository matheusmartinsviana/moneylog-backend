package com.moneylog.core.sk.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public final class ClockTimeProvider implements TimeProvider {

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
