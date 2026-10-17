package com.moneylog.infra.asset.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "asset_balances")
public class AssetBalanceEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "asset_id", nullable = false, updatable = false)
    private UUID assetId;

    @Column(name = "balance_cents", nullable = false, updatable = false)
    private long balanceCents;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)", updatable = false)
    private String currency;

    @Column(name = "balance_date", nullable = false, updatable = false)
    private LocalDate balanceDate;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected AssetBalanceEntity() {
    }

    public AssetBalanceEntity(UUID id, UUID userId, UUID assetId, long balanceCents, String currency, LocalDate balanceDate,
                              Instant recordedAt) {
        this.id = id;
        this.userId = userId;
        this.assetId = assetId;
        this.balanceCents = balanceCents;
        this.currency = currency;
        this.balanceDate = balanceDate;
        this.recordedAt = recordedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public long getBalanceCents() {
        return balanceCents;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getBalanceDate() {
        return balanceDate;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
