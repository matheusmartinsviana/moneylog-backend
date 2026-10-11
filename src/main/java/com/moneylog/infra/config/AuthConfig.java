package com.moneylog.infra.config;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.auth.app.SessionOpener;
import com.moneylog.core.auth.app.gateways.PasswordVerifier;
import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.app.gateways.TokenIssuer;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCase;
import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCaseImpl;
import com.moneylog.core.auth.app.usecase.AuthStatusUseCase;
import com.moneylog.core.auth.app.usecase.AuthStatusUseCaseImpl;
import com.moneylog.core.auth.app.usecase.LoginUseCase;
import com.moneylog.core.auth.app.usecase.PrepareFirstAccessUseCase;
import com.moneylog.core.auth.app.usecase.PrepareFirstAccessUseCaseImpl;
import com.moneylog.core.auth.app.usecase.SetupPasswordUseCase;
import com.moneylog.core.auth.app.usecase.SetupPasswordUseCaseImpl;
import com.moneylog.core.auth.app.usecase.PurgeExpiredRefreshTokensUseCase;
import com.moneylog.core.auth.app.usecase.PurgeExpiredRefreshTokensUseCaseImpl;
import com.moneylog.infra.auth.RefreshTokenPurgeJob;
import com.moneylog.core.auth.app.usecase.LoginUseCaseImpl;
import com.moneylog.core.auth.app.usecase.LogoutUseCase;
import com.moneylog.core.auth.app.usecase.LogoutUseCaseImpl;
import com.moneylog.core.auth.app.usecase.RefreshSessionUseCase;
import com.moneylog.core.auth.app.usecase.RefreshSessionUseCaseImpl;
import com.moneylog.core.auth.exception.AuthExceptions.RefreshInvalidException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.auth.gateways.RefreshTokenGatewayImpl;
import com.moneylog.infra.auth.gateways.UserGatewayImpl;
import com.moneylog.infra.auth.persistence.RefreshTokenRepositoryJpa;
import com.moneylog.infra.auth.persistence.UserRepositoryJpa;
import com.moneylog.infra.security.MoneylogAuthProperties;
import com.moneylog.infra.security.MoneylogOwnerProperties;
import com.moneylog.infra.security.OwnerBootstrap;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class AuthConfig {

    @Bean
    UserGateway userGateway(UserRepositoryJpa repository, TimeProvider time) {
        return new UserGatewayImpl(repository, time);
    }

    @Bean
    RefreshTokenGateway refreshTokenGateway(RefreshTokenRepositoryJpa repository) {
        return new RefreshTokenGatewayImpl(repository);
    }

    @Bean
    SessionOpener sessionOpener(TokenIssuer tokenIssuer, RefreshTokenGateway refreshTokens, IdGenerator ids,
                                MoneylogAuthProperties auth) {
        return new SessionOpener(tokenIssuer, refreshTokens, ids, auth.refreshTtl(), auth.maxSessionAge());
    }

    @Bean
    LoginUseCase loginUseCase(UserGateway users, PasswordVerifier passwordVerifier, SessionOpener sessions, IdGenerator ids,
                              TimeProvider time, PlatformTransactionManager transactionManager) {
        return TransactionalProxies.required(LoginUseCase.class,
                new LoginUseCaseImpl(users, passwordVerifier, sessions, ids, time), transactionManager);
    }

    @Bean
    @SuppressWarnings("unchecked")
    RefreshSessionUseCase refreshSessionUseCase(RefreshTokenGateway refreshTokens, SessionOpener sessions, TimeProvider time,
                                                MoneylogAuthProperties auth, PlatformTransactionManager transactionManager) {
        return TransactionalProxies.requiredCommittingOn(RefreshSessionUseCase.class,
                new RefreshSessionUseCaseImpl(refreshTokens, sessions, time, auth.reuseGrace()), transactionManager,
                RefreshInvalidException.class);
    }

    @Bean
    LogoutUseCase logoutUseCase(RefreshTokenGateway refreshTokens, TimeProvider time, PlatformTransactionManager transactionManager) {
        return TransactionalProxies.required(LogoutUseCase.class, new LogoutUseCaseImpl(refreshTokens, time), transactionManager);
    }

    @Bean
    EnsureOwnerUseCase ensureOwnerUseCase(UserGateway users, IdGenerator ids, RefreshTokenGateway refreshTokens,
                                          TimeProvider time, PlatformTransactionManager transactionManager) {
        return TransactionalProxies.required(EnsureOwnerUseCase.class,
                new EnsureOwnerUseCaseImpl(users, ids, refreshTokens, time), transactionManager);
    }

    @Bean
    PrepareFirstAccessUseCase prepareFirstAccessUseCase(UserGateway users, PasswordVerifier passwordVerifier, TimeProvider time,
                                                        PlatformTransactionManager transactionManager) {
        return TransactionalProxies.required(PrepareFirstAccessUseCase.class,
                new PrepareFirstAccessUseCaseImpl(users, passwordVerifier, time), transactionManager);
    }

    @Bean
    SetupPasswordUseCase setupPasswordUseCase(UserGateway users, PasswordVerifier passwordVerifier,
                                              RefreshTokenGateway refreshTokens, SessionOpener sessions, IdGenerator ids,
                                              TimeProvider time, PlatformTransactionManager transactionManager) {
        return TransactionalProxies.required(SetupPasswordUseCase.class,
                new SetupPasswordUseCaseImpl(users, passwordVerifier, refreshTokens, sessions, ids, time), transactionManager);
    }

    @Bean
    AuthStatusUseCase authStatusUseCase(UserGateway users, PasswordVerifier passwordVerifier,
                                        PlatformTransactionManager transactionManager) {
        return TransactionalProxies.required(AuthStatusUseCase.class, new AuthStatusUseCaseImpl(users, passwordVerifier),
                transactionManager);
    }

    @Bean
    PurgeExpiredRefreshTokensUseCase purgeExpiredRefreshTokensUseCase(RefreshTokenGateway refreshTokens, TimeProvider time,
                                                                      PlatformTransactionManager transactionManager) {
        return TransactionalProxies.required(PurgeExpiredRefreshTokensUseCase.class,
                new PurgeExpiredRefreshTokensUseCaseImpl(refreshTokens, time), transactionManager);
    }

    @Bean
    RefreshTokenPurgeJob refreshTokenPurgeJob(PurgeExpiredRefreshTokensUseCase purge, MoneylogAuthProperties auth) {
        return new RefreshTokenPurgeJob(purge, auth);
    }

    @Bean
    OwnerBootstrap ownerBootstrap(EnsureOwnerUseCase ensureOwner, PrepareFirstAccessUseCase prepareFirstAccess,
                                  MoneylogOwnerProperties owner, ApplicationEventPublisher publisher, Environment environment) {
        return new OwnerBootstrap(ensureOwner, prepareFirstAccess, owner, publisher,
                environment.acceptsProfiles(Profiles.of("dev")));
    }
}
