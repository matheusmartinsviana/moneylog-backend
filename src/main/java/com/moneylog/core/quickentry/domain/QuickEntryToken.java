package com.moneylog.core.quickentry.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class QuickEntryToken {

    public static final int MAX_LABEL_LENGTH = 60;
    public static final int PREFIX_LENGTH = 8;
    public static final int HASH_LENGTH = 64;
    public static final int MAX_USER_AGENT_LENGTH = 120;

    private final UUID id;
    private final UUID userId;
    private final UUID accountId;
    private final String label;
    private final String prefix;
    private final String tokenHash;
    private final QuickEntryScope scope;
    private final Instant expiresAt;
    private final Instant revokedAt;
    private final TokenRevocationReason revokedReason;
    private final Instant lastUsedAt;
    private final String lastUsedIp;
    private final String lastUsedUserAgent;
    private final Instant createdAt;
    private final long version;

    public QuickEntryToken(UUID id, UUID userId, UUID accountId, String label, String prefix, String tokenHash, QuickEntryScope scope,
                           Instant expiresAt, Instant revokedAt, TokenRevocationReason revokedReason, Instant lastUsedAt,
                           String lastUsedIp, String lastUsedUserAgent, Instant createdAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.label = requireLabel(label);
        this.prefix = requireExactLength(prefix, PREFIX_LENGTH, "prefix");
        this.tokenHash = requireExactLength(tokenHash, HASH_LENGTH, "tokenHash");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("a validade do token deve ser posterior à criação");
        }
        if ((revokedAt == null) != (revokedReason == null)) {
            throw new IllegalArgumentException("a revogação exige data e motivo, e só ela os tem");
        }
        this.revokedAt = revokedAt;
        this.revokedReason = revokedReason;
        this.lastUsedAt = lastUsedAt;
        this.lastUsedIp = lastUsedIp;
        this.lastUsedUserAgent = truncate(lastUsedUserAgent);
        this.version = version;
    }

    public static QuickEntryToken issue(UUID id, UUID userId, UUID accountId, String label, String prefix, String tokenHash, Instant now,
                                        Instant expiresAt) {
        return new QuickEntryToken(id, userId, accountId, label, prefix, tokenHash, QuickEntryScope.QUICK_ENTRY, expiresAt, null, null,
                null, null, null, now, 0L);
    }

    public QuickEntryTokenStatus statusAt(Instant now) {
        if (revokedAt != null) {
            return QuickEntryTokenStatus.REVOKED;
        }
        return expiresAt.isAfter(now) ? QuickEntryTokenStatus.ACTIVE : QuickEntryTokenStatus.EXPIRED;
    }

    public boolean isActiveAt(Instant now) {
        return statusAt(now) == QuickEntryTokenStatus.ACTIVE;
    }

    public QuickEntryToken revoke(TokenRevocationReason reason, Instant now) {
        if (revokedAt != null) {
            return this;
        }
        return new QuickEntryToken(id, userId, accountId, label, prefix, tokenHash, scope, expiresAt, Objects.requireNonNull(now, "now"),
                Objects.requireNonNull(reason, "reason"), lastUsedAt, lastUsedIp, lastUsedUserAgent, createdAt, version);
    }

    public QuickEntryToken rename(String newLabel) {
        return new QuickEntryToken(id, userId, accountId, newLabel, prefix, tokenHash, scope, expiresAt, revokedAt, revokedReason,
                lastUsedAt, lastUsedIp, lastUsedUserAgent, createdAt, version);
    }

    public QuickEntryToken usedAt(Instant at, String maskedIp, String userAgent) {
        return new QuickEntryToken(id, userId, accountId, label, prefix, tokenHash, scope, expiresAt, revokedAt, revokedReason,
                Objects.requireNonNull(at, "at"), maskedIp, userAgent, createdAt, version);
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public UUID accountId() {
        return accountId;
    }

    public String label() {
        return label;
    }

    public String prefix() {
        return prefix;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public QuickEntryScope scope() {
        return scope;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public TokenRevocationReason revokedReason() {
        return revokedReason;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    public String lastUsedIp() {
        return lastUsedIp;
    }

    public String lastUsedUserAgent() {
        return lastUsedUserAgent;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }

    @Override
    public String toString() {
        return "QuickEntryToken[id=" + id + ", prefix=" + prefix + "]";
    }

    private static String requireLabel(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("o nome do token é obrigatório");
        }
        String trimmed = value.strip();
        if (trimmed.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException("o nome do token tem no máximo " + MAX_LABEL_LENGTH + " caracteres");
        }
        return trimmed;
    }

    private static String requireExactLength(String value, int length, String field) {
        if (value == null || value.length() != length) {
            throw new IllegalArgumentException(field + " deve ter " + length + " caracteres");
        }
        return value;
    }

    private static String truncate(String userAgent) {
        if (userAgent == null || userAgent.length() <= MAX_USER_AGENT_LENGTH) {
            return userAgent;
        }
        return userAgent.substring(0, MAX_USER_AGENT_LENGTH);
    }
}
