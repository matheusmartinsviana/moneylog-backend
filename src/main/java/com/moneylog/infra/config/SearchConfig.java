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

    @Bean
    SearchRateLimiter searchRateLimiter(Clock clock, @Value("${moneylog.search.rate-limit.max-requests:120}") int maxRequests,
                                        @Value("${moneylog.search.rate-limit.window:PT1M}") Duration window) {
        return new SearchRateLimiter(clock, maxRequests, window);
    }
}
