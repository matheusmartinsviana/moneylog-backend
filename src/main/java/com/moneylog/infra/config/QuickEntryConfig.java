package com.moneylog.infra.config;

import jakarta.persistence.EntityManager;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.quickentry.app.gateways.QuickEntryAuditGateway;
import com.moneylog.core.quickentry.app.gateways.QuickEntryUserLock;
import com.moneylog.core.quickentry.app.usecase.CreateQuickEntryTokenUseCase;
import com.moneylog.core.quickentry.app.usecase.CreateQuickEntryTokenUseCaseImpl;
import com.moneylog.core.quickentry.app.usecase.ListQuickEntryTokensUseCase;
import com.moneylog.core.quickentry.app.usecase.ListQuickEntryTokensUseCaseImpl;
import com.moneylog.core.quickentry.app.usecase.RenameQuickEntryTokenUseCase;
import com.moneylog.core.quickentry.app.usecase.RenameQuickEntryTokenUseCaseImpl;
import com.moneylog.core.quickentry.app.usecase.RevokeQuickEntryTokenUseCase;
import com.moneylog.core.quickentry.app.usecase.RevokeQuickEntryTokenUseCaseImpl;
import com.moneylog.core.quickentry.domain.QuickEntryTokenPolicy;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.infra.quickentry.gateways.QuickEntryUserLockImpl;
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

    @Bean
    QuickEntryTokenPolicy quickEntryTokenPolicy(@Value("${moneylog.quick-entry.default-validity-days:30}") int defaultValidityDays,
                                                @Value("${moneylog.quick-entry.max-validity-days:90}") int maxValidityDays,
                                                @Value("${moneylog.quick-entry.max-active-tokens:3}") int maxActiveTokens) {
        return new QuickEntryTokenPolicy(defaultValidityDays, maxValidityDays, maxActiveTokens);
    }

    @Bean
    QuickEntryUserLock quickEntryUserLock(JdbcTemplate jdbc) {
        return new QuickEntryUserLockImpl(jdbc);
    }

    @Bean
    CreateQuickEntryTokenUseCase createQuickEntryTokenUseCase(QuickEntryTokenGateway tokens, AccountGateway accounts,
                                                              QuickEntryUserLock userLock, QuickEntryTokenPolicy policy, IdGenerator ids,
                                                              TimeProvider time, PlatformTransactionManager transactionManager) {
        return TransactionalProxies.required(CreateQuickEntryTokenUseCase.class,
                new CreateQuickEntryTokenUseCaseImpl(tokens, accounts, userLock, policy, ids, time), transactionManager);
    }

    @Bean
    ListQuickEntryTokensUseCase listQuickEntryTokensUseCase(QuickEntryTokenGateway tokens, AccountGateway accounts, TimeProvider time,
                                                            PlatformTransactionManager transactionManager) {
        return TransactionalProxies.readOnly(ListQuickEntryTokensUseCase.class,
                new ListQuickEntryTokensUseCaseImpl(tokens, accounts, time), transactionManager);
    }

    @Bean
    RenameQuickEntryTokenUseCase renameQuickEntryTokenUseCase(QuickEntryTokenGateway tokens, AccountGateway accounts, QuickEntryUserLock userLock, TimeProvider time,
                                                              PlatformTransactionManager transactionManager) {
        return TransactionalProxies.required(RenameQuickEntryTokenUseCase.class,
                new RenameQuickEntryTokenUseCaseImpl(tokens, accounts, userLock, time), transactionManager);
    }

    @Bean
    RevokeQuickEntryTokenUseCase revokeQuickEntryTokenUseCase(QuickEntryTokenGateway tokens, AccountGateway accounts, QuickEntryUserLock userLock, TimeProvider time,
                                                              PlatformTransactionManager transactionManager) {
        return TransactionalProxies.required(RevokeQuickEntryTokenUseCase.class,
                new RevokeQuickEntryTokenUseCaseImpl(tokens, accounts, userLock, time), transactionManager);
    }
}
