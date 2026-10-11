package com.moneylog.infra.importing.gateways;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.importing.app.gateways.ImportIdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;

public class ImportIdempotencyGatewayImpl implements ImportIdempotencyGateway {

    private final IdempotencyGateway delegate;

    public ImportIdempotencyGatewayImpl(IdempotencyGateway delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean claim(UUID userId, String scope, UUID key, String requestHash, UUID resourceId, Instant now, Instant expiredBefore) {
        return delegate.claim(userId, scope, key, requestHash, resourceId, now, expiredBefore);
    }

    @Override
    public Optional<Claim> find(UUID userId, String scope, UUID key) {
        return delegate.find(userId, scope, key).map(c -> new Claim(c.requestHash(), c.resourceId()));
    }
}
