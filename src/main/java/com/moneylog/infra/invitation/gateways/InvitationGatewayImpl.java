package com.moneylog.infra.invitation.gateways;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.moneylog.core.invitation.app.gateways.InvitationGateway;
import com.moneylog.core.invitation.domain.Invitation;
import com.moneylog.core.invitation.domain.InvitationStatus;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.invitation.persistence.InvitationEntity;
import com.moneylog.infra.invitation.persistence.InvitationRepositoryJpa;

public class InvitationGatewayImpl implements InvitationGateway {

    private static final long ISSUANCE_LOCK = 0x4d4c49564954L;

    private final InvitationRepositoryJpa repository;
    private final JdbcTemplate jdbc;
    private final TimeProvider time;
    private final TransactionTemplate independent;
    private final EntityManager entityManager;

    public InvitationGatewayImpl(InvitationRepositoryJpa repository, JdbcTemplate jdbc, TimeProvider time,
                                 PlatformTransactionManager transactionManager, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
        this.time = time;
        this.independent = new TransactionTemplate(transactionManager);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void lockIssuance() {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, ISSUANCE_LOCK);
    }

    @Override
    public Optional<Invitation> findByIdForUpdate(UUID id) {
        return repository.findByIdForUpdate(id).map(this::detachedDomain);
    }

    @Override
    public Optional<Invitation> findByTokenHash(String tokenHash) {
        return repository.findByTokenHash(tokenHash).map(this::detachedDomain);
    }

    @Override
    public Optional<Invitation> findByTokenHashForUpdate(String tokenHash) {
        return repository.findByTokenHashForUpdate(tokenHash).map(this::detachedDomain);
    }

    @Override
    public Optional<Invitation> findPendingByEmail(String email) {
        return repository.findByEmailAndStatus(email, InvitationStatus.PENDING).map(this::detachedDomain);
    }

    @Override
    public List<Invitation> findAllNewestFirst() {
        return repository.findAllByOrderByCreatedAtDescIdAsc().stream().map(this::detachedDomain).toList();
    }

    @Override
    public long countPendingNotExpired(Instant now) {
        return repository.countPendingNotExpired(now);
    }

    @Override
    public int expireOverdueOf(String email, Instant now) {
        return repository.expireOverdueOf(email, now);
    }

    @Override
    public int expireOverdue(Instant now) {
        return repository.expireOverdue(now);
    }

    @Override
    public void insert(Invitation invitation) {
        repository.saveAndFlush(InvitationRepositoryMapper.toEntity(invitation));
    }

    @Override
    public void update(Invitation invitation) {
        InvitationEntity entity = repository.findById(invitation.id()).orElseThrow();
        entity.apply(invitation.tokenHash(), invitation.status(), invitation.expiresAt(), invitation.lastSentAt(),
                invitation.deliveryStatus(), invitation.acceptedAt(), invitation.acceptedUserId(), time.now());
        repository.saveAndFlush(entity);
    }

    @Override
    public boolean markAccepted(Invitation accepted) {
        return repository.markAccepted(accepted.id(), accepted.acceptedUserId(), accepted.acceptedAt()) == 1;
    }

    @Override
    public void revokeInIndependentTransaction(UUID id) {
        Instant now = time.now();
        independent.executeWithoutResult(status -> repository.revokeIfPending(id, now));
    }

    @Override
    public int deleteFinalBefore(Instant cutoff) {
        return repository.deleteFinalBefore(cutoff);
    }

    private Invitation detachedDomain(InvitationEntity entity) {
        Invitation invitation = InvitationRepositoryMapper.toDomain(entity);
        entityManager.detach(entity);
        return invitation;
    }
}
