package com.moneylog.infra.quickentry.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.moneylog.core.quickentry.domain.QuickEntryScope;
import com.moneylog.core.quickentry.domain.TokenRevocationReason;

@Entity
@Table(name = "quick_entry_tokens")
public class QuickEntryTokenEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, length = 60)
    private String label;

    @Column(nullable = false, updatable = false, length = 8)
    private String prefix;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private QuickEntryScope scope;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 16)
    private TokenRevocationReason revokedReason;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "last_used_ip", length = 45)
    private String lastUsedIp;

    @Column(name = "last_used_user_agent", length = 120)
    private String lastUsedUserAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected QuickEntryTokenEntity() {
    }

    public QuickEntryTokenEntity(UUID id, UUID userId, UUID accountId, String label, String prefix, String tokenHash,
                                 QuickEntryScope scope, Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.accountId = accountId;
        this.prefix = prefix;
        this.tokenHash = tokenHash;
        this.scope = scope;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.label = label;
        this.updatedAt = createdAt;
    }

    public void apply(String newLabel, Instant newRevokedAt, TokenRevocationReason newRevokedReason, Instant newLastUsedAt,
                      String newLastUsedIp, String newLastUsedUserAgent, Instant now) {
        this.label = newLabel;
        this.revokedAt = newRevokedAt;
        this.revokedReason = newRevokedReason;
        this.lastUsedAt = newLastUsedAt;
        this.lastUsedIp = newLastUsedIp;
        this.lastUsedUserAgent = newLastUsedUserAgent;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getLabel() {
        return label;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public QuickEntryScope getScope() {
        return scope;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public TokenRevocationReason getRevokedReason() {
        return revokedReason;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public String getLastUsedIp() {
        return lastUsedIp;
    }

    public String getLastUsedUserAgent() {
        return lastUsedUserAgent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }
}
