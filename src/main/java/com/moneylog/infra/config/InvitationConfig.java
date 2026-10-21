package com.moneylog.infra.config;

import java.time.Clock;
import java.time.Duration;

import jakarta.persistence.EntityManager;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.auth.app.SessionOpener;
import com.moneylog.core.auth.app.gateways.PasswordVerifier;
import com.moneylog.core.auth.app.gateways.UserCreatedPublisher;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.invitation.app.InvitationDispatcher;
import com.moneylog.core.invitation.app.InvitationIssuer;
import com.moneylog.core.invitation.app.InvitationIssuerImpl;
import com.moneylog.core.invitation.app.InvitationPolicy;
import com.moneylog.core.invitation.app.gateways.InvitationGateway;
import com.moneylog.core.invitation.app.gateways.InviterNameGateway;
import com.moneylog.core.invitation.app.usecase.AcceptInvitationUseCase;
import com.moneylog.core.invitation.app.usecase.AcceptInvitationUseCaseImpl;
import com.moneylog.core.invitation.app.usecase.InviteUserUseCase;
import com.moneylog.core.invitation.app.usecase.InviteUserUseCaseImpl;
import com.moneylog.core.invitation.app.usecase.ListInvitationsUseCase;
import com.moneylog.core.invitation.app.usecase.ListInvitationsUseCaseImpl;
import com.moneylog.core.invitation.app.usecase.PreviewInvitationUseCase;
import com.moneylog.core.invitation.app.usecase.PreviewInvitationUseCaseImpl;
import com.moneylog.core.invitation.app.usecase.PurgeInvitationsUseCase;
import com.moneylog.core.invitation.app.usecase.PurgeInvitationsUseCaseImpl;
import com.moneylog.core.invitation.app.usecase.ResendInvitationUseCase;
import com.moneylog.core.invitation.app.usecase.ResendInvitationUseCaseImpl;
import com.moneylog.core.invitation.app.usecase.RevokeInvitationUseCase;
import com.moneylog.core.invitation.app.usecase.RevokeInvitationUseCaseImpl;
import com.moneylog.core.mail.app.EmailTemplate;
import com.moneylog.core.mail.app.gateways.EmailSender;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.invitation.SpringUserCreatedPublisher;
import com.moneylog.infra.invitation.controller.InvitationNoStoreFilter;
import com.moneylog.infra.invitation.gateways.InvitationGatewayImpl;
import com.moneylog.infra.invitation.gateways.InviterNameGatewayImpl;
import com.moneylog.infra.invitation.job.InvitationRetentionJob;
import com.moneylog.infra.invitation.persistence.InvitationRepositoryJpa;
import com.moneylog.infra.security.InvitationRateLimiter;
import com.moneylog.infra.security.LoginRateLimiter;
import com.moneylog.infra.security.MoneylogAuthProperties;

@Configuration(proxyBeanMethods = false)
public class InvitationConfig {

    @Bean
    InvitationPolicy invitationPolicy(@Value("${moneylog.invitations.enabled:true}") boolean enabled,
                                      @Value("${moneylog.invitations.validity-days:7}") int validityDays,
                                      @Value("${moneylog.users.max:10}") int maxUsers) {
        return new InvitationPolicy(enabled, validityDays, maxUsers);
    }

    @Bean
    InvitationGateway invitationGateway(InvitationRepositoryJpa repository, JdbcTemplate jdbc, TimeProvider time,
                                        PlatformTransactionManager transactionManager, EntityManager entityManager) {
        return new InvitationGatewayImpl(repository, jdbc, time, transactionManager, entityManager);
    }

    @Bean
    InviterNameGateway inviterNameGateway(JdbcTemplate jdbc) {
        return new InviterNameGatewayImpl(jdbc);
    }

    @Bean
    UserCreatedPublisher userCreatedPublisher(ApplicationEventPublisher publisher) {
        return new SpringUserCreatedPublisher(publisher);
    }

    @Bean
    InvitationIssuer invitationIssuer(InvitationGateway invitations, UserGateway users, InviterNameGateway inviterNames,
                                      IdGenerator ids, TimeProvider time, InvitationPolicy policy, PlatformTransactionManager transactionManager) {
        return TransactionalProxies.required(InvitationIssuer.class,
                new InvitationIssuerImpl(invitations, users, inviterNames, ids, time, policy), transactionManager);
    }

    @Bean
    InvitationDispatcher invitationDispatcher(InvitationIssuer issuer, EmailSender sender, EmailTemplate template, TimeProvider time,
                                              InvitationPolicy policy) {
        return new InvitationDispatcher(issuer, sender, template, time, policy);
    }

    @Bean
    InviteUserUseCase inviteUserUseCase(InvitationIssuer issuer, InvitationDispatcher dispatcher) {
        return new InviteUserUseCaseImpl(issuer, dispatcher);
    }

    @Bean
    ResendInvitationUseCase resendInvitationUseCase(InvitationIssuer issuer, InvitationDispatcher dispatcher) {
        return new ResendInvitationUseCaseImpl(issuer, dispatcher);
    }

    @Bean
    RevokeInvitationUseCase revokeInvitationUseCase(InvitationGateway invitations, TimeProvider time,
                                                    PlatformTransactionManager transactionManager) {
        return TransactionalProxies.required(RevokeInvitationUseCase.class, new RevokeInvitationUseCaseImpl(invitations, time),
                transactionManager);
    }

    @Bean
    ListInvitationsUseCase listInvitationsUseCase(InvitationGateway invitations, TimeProvider time,
                                                  PlatformTransactionManager transactionManager) {
        return TransactionalProxies.readOnly(ListInvitationsUseCase.class, new ListInvitationsUseCaseImpl(invitations, time),
                transactionManager);
    }

    @Bean
    PreviewInvitationUseCase previewInvitationUseCase(InvitationGateway invitations, InviterNameGateway inviterNames, TimeProvider time, InvitationPolicy policy,
                                                      PlatformTransactionManager transactionManager) {
        return TransactionalProxies.readOnly(PreviewInvitationUseCase.class,
                new PreviewInvitationUseCaseImpl(invitations, inviterNames, time, policy), transactionManager);
    }

    @Bean
    AcceptInvitationUseCase acceptInvitationUseCase(InvitationGateway invitations, UserGateway users, PasswordVerifier passwordVerifier,
                                                    SessionOpener sessions, UserCreatedPublisher userCreated, IdGenerator ids,
                                                    TimeProvider time, InvitationPolicy policy,
                                                    PlatformTransactionManager transactionManager) {
        return TransactionalProxies.required(AcceptInvitationUseCase.class,
                new AcceptInvitationUseCaseImpl(invitations, users, passwordVerifier, sessions, userCreated, ids, time, policy),
                transactionManager);
    }

    @Bean
    PurgeInvitationsUseCase purgeInvitationsUseCase(InvitationGateway invitations, TimeProvider time,
                                                    PlatformTransactionManager transactionManager) {
        return TransactionalProxies.required(PurgeInvitationsUseCase.class, new PurgeInvitationsUseCaseImpl(invitations, time),
                transactionManager);
    }

    @Bean
    InvitationRetentionJob invitationRetentionJob(PurgeInvitationsUseCase purge,
                                                  @Value("${moneylog.invitations.retention-days:90}") int retentionDays) {
        return new InvitationRetentionJob(purge, Duration.ofDays(retentionDays));
    }

    @Bean
    InvitationRateLimiter invitationRateLimiter(Clock clock, MoneylogAuthProperties auth) {
        return new InvitationRateLimiter(clock, new LoginRateLimiter.Settings(auth.maxLoginFailures(), auth.loginWindow(),
                auth.maxLoginFailures(), auth.accountLockBase(), auth.accountLockMax(), auth.trustedIpTtl(), auth.limiterMaxEntries()));
    }

    @Bean
    FilterRegistrationBean<InvitationNoStoreFilter> invitationNoStoreFilter() {
        FilterRegistrationBean<InvitationNoStoreFilter> registration = new FilterRegistrationBean<>(new InvitationNoStoreFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 3);
        return registration;
    }
}
