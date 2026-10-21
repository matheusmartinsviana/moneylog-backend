package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.app.usecase.DisableUserUseCase;
import com.moneylog.core.auth.app.usecase.DisableUserUseCaseImpl;
import com.moneylog.core.auth.app.usecase.EnableUserUseCase;
import com.moneylog.core.auth.app.usecase.EnableUserUseCaseImpl;
import com.moneylog.core.auth.app.usecase.ListUsersUseCase;
import com.moneylog.core.auth.app.usecase.ListUsersUseCaseImpl;
import com.moneylog.core.quickentry.app.gateways.QuickEntryTokenGateway;
import com.moneylog.core.quickentry.app.gateways.QuickEntryUserLock;
import com.moneylog.core.sk.time.TimeProvider;

@Configuration(proxyBeanMethods = false)
public class UserAdminConfig {

    @Bean
    ListUsersUseCase listUsersUseCase(UserGateway users, PlatformTransactionManager transactionManager) {
        return TransactionalProxies.readOnly(ListUsersUseCase.class, new ListUsersUseCaseImpl(users), transactionManager);
    }

    @Bean
    DisableUserUseCase disableUserUseCase(UserGateway users, RefreshTokenGateway refreshTokens, QuickEntryTokenGateway quickEntryTokens, QuickEntryUserLock quickEntryLock, TimeProvider time,
                                          PlatformTransactionManager transactionManager) {
        return TransactionalProxies.required(DisableUserUseCase.class,
                new DisableUserUseCaseImpl(users, refreshTokens, quickEntryTokens, quickEntryLock, time), transactionManager);
    }

    @Bean
    EnableUserUseCase enableUserUseCase(UserGateway users, TimeProvider time, PlatformTransactionManager transactionManager) {
        return TransactionalProxies.required(EnableUserUseCase.class, new EnableUserUseCaseImpl(users, time), transactionManager);
    }
}
