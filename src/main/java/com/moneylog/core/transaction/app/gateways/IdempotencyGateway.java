package com.moneylog.core.transaction.app.gateways;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyGateway {

    record Claim(String requestHash, UUID resourceId, Instant createdAt) {
    }

    boolean claim(UUID userId, String scope, UUID key, String requestHash, UUID resourceId, Instant now, Instant expiredBefore);

    Optional<Claim> find(UUID userId, String scope, UUID key);
}
