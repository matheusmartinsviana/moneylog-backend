package com.moneylog.core.transaction.app.gateways;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Chaves de idempotência (S-72, S-87). A chave é gravada na mesma transação da criação do recurso: se a criação
 * falha, a chave sai no rollback (erros não são memorizados). Todo método filtra pelo {@code userId}.
 */
public interface IdempotencyGateway {

    /** Chave já gravada: o hash do comando que a usou e o recurso que ele criou. */
    record Claim(String requestHash, UUID resourceId, Instant createdAt) {
    }

    /**
     * Reivindica a chave para {@code resourceId}. Atômico: {@code true} se a chave não existia (ou existia vencida, com
     * {@code createdAt <= expiredBefore}, e foi sobrescrita); {@code false} se existe uma chave válida (inclusive a de
     * uma transação concorrente, cujo commit ou rollback esta chamada espera).
     */
    boolean claim(UUID userId, String scope, UUID key, String requestHash, UUID resourceId, Instant now, Instant expiredBefore);

    /** A chave gravada (depois de um {@link #claim} recusado). */
    Optional<Claim> find(UUID userId, String scope, UUID key);
}
