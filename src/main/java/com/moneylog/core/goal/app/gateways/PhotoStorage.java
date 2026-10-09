package com.moneylog.core.goal.app.gateways;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta dos bytes das fotos (ADR-018). Os metadados (posição, dimensões, tamanho) ficam no agregado {@code Goal}; aqui moram só os bytes
 * processados (exibição e miniatura, JPEG). O adaptador atual guarda em tabela própria do Postgres ({@code bytea}); um adaptador de object
 * storage (S3/R2) entra no lugar sem mexer no core. Todo método filtra pelo dono; foto de outro usuário não existe.
 */
public interface PhotoStorage {

    enum Variant {
        DISPLAY, THUMBNAIL
    }

    /** Grava os bytes de uma foto cujos metadados já foram gravados na mesma transação. */
    void put(UUID userId, UUID goalId, UUID photoId, byte[] display, byte[] thumbnail);

    /** Os bytes da variante, se a foto existe, é da meta e é do usuário. */
    Optional<byte[]> get(UUID userId, UUID goalId, UUID photoId, Variant variant);

    /** Apaga os bytes (idempotente). */
    void delete(UUID userId, UUID photoId);
}
