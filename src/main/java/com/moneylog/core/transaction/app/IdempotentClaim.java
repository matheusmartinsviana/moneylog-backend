package com.moneylog.core.transaction.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;
import com.moneylog.core.transaction.exception.TransactionExceptions.IdempotencyKeyReusedException;

public final class IdempotentClaim {

    public static final Duration VALIDITY = Duration.ofHours(24);

    private IdempotentClaim() {
    }

    public static Claim claimOrFind(IdempotencyGateway idempotency, UUID userId, String scope, UUID key, String hash, UUID resourceId,
                                    Instant now) {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (idempotency.claim(userId, scope, key, hash, resourceId, now, now.minus(VALIDITY))) {
                return null;
            }
            var found = idempotency.find(userId, scope, key);
            if (found.isPresent()) {
                return found.get();
            }
        }
        throw new IllegalStateException("não foi possível reivindicar nem ler a chave de idempotência");
    }

    public static void requireSameCommand(Claim existing, String hash) {
        if (!existing.requestHash().equals(hash)) {
            throw new IdempotencyKeyReusedException();
        }
    }

    public static String hash(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
