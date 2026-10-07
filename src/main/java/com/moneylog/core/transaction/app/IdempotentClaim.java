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

/**
 * Mecanismo comum da {@code Idempotency-Key} das criações (S-72, S-87, S-237): reivindica a chave (na mesma transação da criação),
 * confere o hash do comando canônico e devolve o recurso atual no replay. Cada rota tem o seu escopo.
 */
public final class IdempotentClaim {

    /** Validade da chave (S-72). */
    public static final Duration VALIDITY = Duration.ofHours(24);

    private IdempotentClaim() {
    }

    /** {@code null} = a chave é nossa (primeira vez ou vencida); senão, o que ela guardou. */
    public static Claim claimOrFind(IdempotencyGateway idempotency, UUID userId, String scope, UUID key, String hash, UUID resourceId,
                                    Instant now) {
        // a chave some entre o claim recusado e o find só se a transação que a segurava deu rollback: tenta de novo
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

    /** A mesma chave só vale para o mesmo comando; outro comando é {@code IDEMPOTENCY_KEY_REUSED}. */
    public static void requireSameCommand(Claim existing, String hash) {
        if (!existing.requestHash().equals(hash)) {
            throw new IdempotencyKeyReusedException();
        }
    }

    /** SHA-256 em hexadecimal do comando canônico. */
    public static String hash(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
