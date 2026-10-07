package com.moneylog.util;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Relógio fixo para ITs que dependem de "hoje" (importe com {@code @Import(FixedClockConfig.class)}).
 * O instante fixo é {@value #INSTANT}: em UTC já é 2026-10-11, mas em {@code America/Sao_Paulo} (UTC−3) ainda é
 * 2026-10-10, o que prova que "hoje" usa o fuso do usuário.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockConfig {

    public static final String INSTANT = "2026-10-11T01:30:00Z";

    @Bean
    @Primary
    Clock fixedClock() {
        return Clock.fixed(Instant.parse(INSTANT), ZoneOffset.UTC);
    }
}
