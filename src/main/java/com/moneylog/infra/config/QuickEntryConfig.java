package com.moneylog.infra.config;

import jakarta.persistence.EntityManager;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.quickentry.app.gateways.QuickEntryAuditGateway;
import com.moneylog.core.quickentry.app.gateways.QuickEntryTokenGateway;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.quickentry.gateways.QuickEntryAuditGatewayImpl;
import com.moneylog.infra.quickentry.gateways.QuickEntryTokenGatewayImpl;
import com.moneylog.infra.quickentry.persistence.QuickEntryAuditRepositoryJpa;
import com.moneylog.infra.quickentry.persistence.QuickEntryTokenRepositoryJpa;

@Configuration(proxyBeanMethods = false)
public class QuickEntryConfig {

    @Bean
    QuickEntryTokenGateway quickEntryTokenGateway(QuickEntryTokenRepositoryJpa repository, TimeProvider time, EntityManager entityManager,
                                                  PlatformTransactionManager transactionManager) {
        return TransactionalProxies.required(QuickEntryTokenGateway.class,
                new QuickEntryTokenGatewayImpl(repository, time, entityManager), transactionManager);
    }

    @Bean
    QuickEntryAuditGateway quickEntryAuditGateway(QuickEntryAuditRepositoryJpa repository, PlatformTransactionManager transactionManager) {
        return TransactionalProxies.required(QuickEntryAuditGateway.class, new QuickEntryAuditGatewayImpl(repository), transactionManager);
    }
}
