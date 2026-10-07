package com.moneylog.infra.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.moneylog.core.sk.time.ClockTimeProvider;
import com.moneylog.core.sk.time.TimeProvider;

/**
 * Relógio da aplicação (D1 §1.2, S-1): "hoje" é calculado em {@code moneylog.zone} ({@code America/Sao_Paulo}) e o
 * instante técnico é UTC. Todo código lê o tempo pelo {@link TimeProvider}; os ITs fixam o relógio declarando um
 * bean {@link Clock} {@code @Primary} (veja {@code FixedClockConfig} nos testes).
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TimeProvider timeProvider(Clock clock, @Value("${moneylog.zone}") String zone) {
        return new ClockTimeProvider(clock, ZoneId.of(zone));
    }
}
