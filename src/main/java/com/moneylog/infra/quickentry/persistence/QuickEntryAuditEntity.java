package com.moneylog.infra.quickentry.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "quick_entry_audit")
public class QuickEntryAuditEntity implements Persistable<UUID> {

    @Id
    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "token_id", nullable = false, updatable = false)
    private UUID tokenId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(length = 45, updatable = false)
    private String ip;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean persisted;

    protected QuickEntryAuditEntity() {
    }

    public QuickEntryAuditEntity(UUID transactionId, UUID tokenId, UUID userId, String ip, Instant createdAt) {
        this.transactionId = transactionId;
        this.tokenId = tokenId;
        this.userId = userId;
        this.ip = ip;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return transactionId;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.persisted = true;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getTokenId() {
        return tokenId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getIp() {
        return ip;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
