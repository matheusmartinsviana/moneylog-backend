package com.moneylog.util;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class MutableClockConfig {

    public static final Instant INITIAL = Instant.parse("2026-10-10T15:00:00Z");

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(INITIAL);
    }
}
