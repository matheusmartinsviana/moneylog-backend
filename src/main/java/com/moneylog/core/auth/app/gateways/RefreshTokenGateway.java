package com.moneylog.core.auth.app.gateways;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.auth.domain.RefreshToken;

public interface RefreshTokenGateway {

    void insert(RefreshToken token);

    /** Busca pelo hash travando a linha ({@code FOR UPDATE}): a rotação é serializada por token (S-83). */
    Optional<RefreshToken> findByHashForUpdate(String tokenHash);

    void markReplaced(UUID id, UUID replacedBy, Instant at);

    /** Apaga os tokens que expiraram antes de {@code cutoff} (B7); devolve quantos. */
    int deleteExpiredBefore(Instant cutoff);

    /** Revoga todos os tokens ainda não revogados da família. */
    int revokeFamily(UUID familyId, Instant at);

    /** Revoga todos os tokens ainda não revogados do usuário (troca de senha); devolve quantos. */
    int revokeAllByUser(UUID userId, Instant at);
}
