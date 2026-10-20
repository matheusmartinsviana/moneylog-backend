package com.moneylog.core.auth.app.gateways;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.auth.domain.RefreshToken;

public interface RefreshTokenGateway {

    void insert(RefreshToken token);

    Optional<UUID> findUserIdByHash(String tokenHash);

    Optional<RefreshToken> findByHashForUpdate(String tokenHash);

    void markReplaced(UUID id, UUID replacedBy, Instant at);

    int deleteExpiredBefore(Instant cutoff);

    int revokeFamily(UUID familyId, Instant at);

    int revokeAllByUser(UUID userId, Instant at);
}
