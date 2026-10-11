package com.moneylog.util;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class FixedClockConfig {

    public static final String INSTANT = "2026-10-11T01:30:00Z";

    @Bean
    @Primary
    Clock fixedClock() {
        return Clock.fixed(Instant.parse(INSTANT), ZoneOffset.UTC);
    }
}
