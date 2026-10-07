package com.moneylog.core.auth.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Refresh token opaco (ADR-003). Só o hash SHA-256 do valor é guardado. Cada uso rotaciona: o token antigo fica
 * "substituído" ({@code replacedAt}) e a família ({@code familyId}) é revogada inteira se o antigo reaparecer fora da
 * janela de tolerância (S-83).
 */
public record RefreshToken(UUID id, UUID userId, UUID familyId, String tokenHash, Instant createdAt, Instant expiresAt,
                           Instant revokedAt, UUID replacedBy, Instant replacedAt, Instant familyStartedAt) {

    public RefreshToken {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(familyId, "familyId");
        Objects.requireNonNull(tokenHash, "tokenHash");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(familyStartedAt, "familyStartedAt");
    }

    /** Token de uma família que começa neste instante (o início da família é o próprio {@code createdAt}). */
    public RefreshToken(UUID id, UUID userId, UUID familyId, String tokenHash, Instant createdAt, Instant expiresAt,
                        Instant revokedAt, UUID replacedBy, Instant replacedAt) {
        this(id, userId, familyId, tokenHash, createdAt, expiresAt, revokedAt, replacedBy, replacedAt, createdAt);
    }

    public static RefreshToken issue(UUID id, UUID userId, UUID familyId, String tokenHash, Instant now, Instant expiresAt) {
        return new RefreshToken(id, userId, familyId, tokenHash, now, expiresAt, null, null, null, now);
    }

    /** Sucessor numa família que começou em {@code familyStartedAt} (rotação). */
    public static RefreshToken issue(UUID id, UUID userId, UUID familyId, String tokenHash, Instant now, Instant expiresAt,
                                     Instant familyStartedAt) {
        return new RefreshToken(id, userId, familyId, tokenHash, now, expiresAt, null, null, null, familyStartedAt);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isReplaced() {
        return replacedAt != null;
    }
}
