package com.moneylog.infra.quickentry.gateways;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.moneylog.core.quickentry.app.gateways.QuickEntryTokenGateway;
import com.moneylog.core.quickentry.domain.IpMask;
import com.moneylog.core.quickentry.domain.QuickEntryToken;
import com.moneylog.core.quickentry.domain.TokenRevocationReason;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.quickentry.persistence.QuickEntryTokenEntity;
import com.moneylog.infra.quickentry.persistence.QuickEntryTokenRepositoryJpa;

public class QuickEntryTokenGatewayImpl implements QuickEntryTokenGateway {

    private final QuickEntryTokenRepositoryJpa repository;
    private final TimeProvider time;
    private final EntityManager entityManager;

    public QuickEntryTokenGatewayImpl(QuickEntryTokenRepositoryJpa repository, TimeProvider time, EntityManager entityManager) {
        this.repository = repository;
        this.time = time;
        this.entityManager = entityManager;
    }

    @Override
    public void insert(QuickEntryToken token) {
        repository.saveAndFlush(QuickEntryTokenRepositoryMapper.toEntity(token));
    }

    @Override
    public void update(QuickEntryToken token) {
        QuickEntryTokenEntity entity = repository.findByUserIdAndId(token.userId(), token.id()).orElseThrow();
        if (entity.getVersion() == null || entity.getVersion() != token.version()) {
            throw new ObjectOptimisticLockingFailureException(QuickEntryTokenEntity.class, token.id());
        }
        entity.applyChange(token.label(), token.revokedAt(), token.revokedReason(), time.now());
        repository.saveAndFlush(entity);
    }

    @Override
    public Optional<QuickEntryToken> findById(UUID userId, UUID id) {
        return repository.findByUserIdAndId(userId, id).map(this::detachedDomain);
    }

    @Override
    public Optional<QuickEntryToken> findByTokenHash(UUID userId, String tokenHash) {
        return repository.findByUserIdAndTokenHash(userId, tokenHash).map(this::detachedDomain);
    }

    @Override
    public Optional<QuickEntryToken> findByTokenHashForAuthentication(String tokenHash) {
        return repository.findByTokenHash(tokenHash).map(this::detachedDomain);
    }

    @Override
    public List<QuickEntryToken> listNewestFirst(UUID userId) {
        return repository.findByUserIdOrderByCreatedAtDescIdAsc(userId).stream().map(this::detachedDomain).toList();
    }

    @Override
    public long countActive(UUID userId, Instant now) {
        return repository.countActive(userId, now);
    }

    @Override
    public int revokeAllActive(UUID userId, TokenRevocationReason reason, Instant now) {
        return repository.revokeAllActive(userId, reason, now);
    }

    @Override
    public boolean recordUse(UUID userId, UUID id, Instant at, Instant usedBefore, String maskedIp, String userAgent) {
        return repository.recordUse(userId, id, at, usedBefore, IpMask.mask(maskedIp), truncate(userAgent)) == 1;
    }

    private static String truncate(String userAgent) {
        if (userAgent == null || userAgent.length() <= QuickEntryToken.MAX_USER_AGENT_LENGTH) {
            return userAgent;
        }
        return userAgent.substring(0, QuickEntryToken.MAX_USER_AGENT_LENGTH);
    }

    private QuickEntryToken detachedDomain(QuickEntryTokenEntity entity) {
        QuickEntryToken token = QuickEntryTokenRepositoryMapper.toDomain(entity);
        entityManager.detach(entity);
        return token;
    }
}
