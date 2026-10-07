package com.moneylog.infra.config;

import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.moneylog.core.sk.id.IdGenerator;

/** Ids gerados pela aplicação (nunca pelo banco). */
@Configuration(proxyBeanMethods = false)
public class IdConfig {

    @Bean
    IdGenerator idGenerator() {
        return UUID::randomUUID;
    }
}
