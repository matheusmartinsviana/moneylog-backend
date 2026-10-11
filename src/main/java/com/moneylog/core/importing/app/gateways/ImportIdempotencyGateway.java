package com.moneylog.core.importing.app.gateways;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ImportIdempotencyGateway {

    record Claim(String requestHash, UUID resourceId) {
    }

    boolean claim(UUID userId, String scope, UUID key, String requestHash, UUID resourceId, Instant now, Instant expiredBefore);

    Optional<Claim> find(UUID userId, String scope, UUID key);
}
