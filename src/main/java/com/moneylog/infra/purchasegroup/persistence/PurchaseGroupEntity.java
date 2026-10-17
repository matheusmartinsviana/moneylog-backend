package com.moneylog.infra.purchasegroup.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "purchase_groups")
public class PurchaseGroupEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private boolean interest;

    @Column(name = "declared_total_cents", updatable = false)
    private Long declaredTotalCents;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected PurchaseGroupEntity() {
    }

    public PurchaseGroupEntity(UUID id, UUID userId, String name, boolean interest, Long declaredTotalCents, Instant now) {
        this.id = id;
        this.userId = userId;
        this.declaredTotalCents = declaredTotalCents;
        this.createdAt = now;
        apply(name, interest, now);
    }

    public void apply(String newName, boolean newInterest, Instant now) {
        this.name = newName;
        this.interest = newInterest;
        this.updatedAt = updatedAt == null || now.isAfter(updatedAt) ? now : updatedAt.plus(1, ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public boolean isInterest() {
        return interest;
    }

    public Long getDeclaredTotalCents() {
        return declaredTotalCents;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
