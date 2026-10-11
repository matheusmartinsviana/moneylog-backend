package com.moneylog.infra.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.moneylog.core.sk.time.ClockTimeProvider;
import com.moneylog.core.sk.time.TimeProvider;

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
