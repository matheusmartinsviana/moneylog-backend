package com.moneylog.infra.auth.gateways;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.domain.RefreshToken;
import com.moneylog.infra.auth.persistence.RefreshTokenEntity;
import com.moneylog.infra.auth.persistence.RefreshTokenRepositoryJpa;

public class RefreshTokenGatewayImpl implements RefreshTokenGateway {

    private final RefreshTokenRepositoryJpa repository;

    public RefreshTokenGatewayImpl(RefreshTokenRepositoryJpa repository) {
        this.repository = repository;
    }

    @Override
    public void insert(RefreshToken token) {
        repository.saveAndFlush(new RefreshTokenEntity(token.id(), token.userId(), token.familyId(), token.tokenHash(),
                token.createdAt(), token.expiresAt(), token.familyStartedAt()));
    }

    @Override
    public Optional<UUID> findUserIdByHash(String tokenHash) {
        return repository.findUserIdByHash(tokenHash);
    }

    @Override
    public Optional<RefreshToken> findByHashForUpdate(String tokenHash) {
        return repository.findByHashForUpdate(tokenHash).map(RefreshTokenGatewayImpl::toDomain);
    }

    @Override
    public void markReplaced(UUID id, UUID replacedBy, Instant at) {
        repository.markReplaced(id, replacedBy, at);
    }

    @Override
    public int deleteExpiredBefore(Instant cutoff) {
        return repository.deleteExpiredBefore(cutoff);
    }

    @Override
    public int revokeFamily(UUID familyId, Instant at) {
        return repository.revokeFamily(familyId, at);
    }

    @Override
    public int revokeAllByUser(UUID userId, Instant at) {
        return repository.revokeAllByUser(userId, at);
    }

    private static RefreshToken toDomain(RefreshTokenEntity e) {
        return new RefreshToken(e.getId(), e.getUserId(), e.getFamilyId(), e.getTokenHash(), e.getCreatedAt(),
                e.getExpiresAt(), e.getRevokedAt(), e.getReplacedBy(), e.getReplacedAt(),
                e.getFamilyStartedAt() == null ? e.getCreatedAt() : e.getFamilyStartedAt());
    }
}
