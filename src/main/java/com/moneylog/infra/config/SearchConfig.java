package com.moneylog.infra.config;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.search.app.SearchUseCase;
import com.moneylog.core.search.app.SearchUseCaseImpl;
import com.moneylog.core.search.app.gateways.SearchGateway;
import com.moneylog.infra.search.controller.SearchRateLimiter;
import com.moneylog.infra.search.gateways.SearchGatewayImpl;

/**
 * Ligação da busca global (T186): leitura pura num instantâneo só ({@code REPEATABLE_READ} somente leitura, ADR-008: as cinco consultas veem o
 * mesmo estado do banco), com limite de taxa próprio de 120 pedidos por minuto por usuário (S-287) e limite de tempo por grupo.
 */
@Configuration(proxyBeanMethods = false)
public class SearchConfig {

    @Bean
    SearchGateway searchGateway(JdbcTemplate jdbc, @Value("${moneylog.search.timeout:PT3S}") Duration timeout) {
        return new SearchGatewayImpl(jdbc, timeout);
    }

    @Bean
    SearchUseCase searchUseCase(SearchGateway gateway, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(SearchUseCase.class, new SearchUseCaseImpl(gateway), tm);
    }

    /** S-287: 120 pedidos de busca por usuário por minuto (em memória, como o login e a exportação). */
    @Bean
    SearchRateLimiter searchRateLimiter(Clock clock, @Value("${moneylog.search.rate-limit.max-requests:120}") int maxRequests,
                                        @Value("${moneylog.search.rate-limit.window:PT1M}") Duration window) {
        return new SearchRateLimiter(clock, maxRequests, window);
    }
}
