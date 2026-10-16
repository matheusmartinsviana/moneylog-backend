package com.moneylog.infra.transaction.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "transaction_foreign_charges")
public class ForeignChargeEntity {

    @Id
    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "original_amount_cents", nullable = false, updatable = false)
    private long originalAmountCents;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "original_currency", nullable = false, length = 3, columnDefinition = "char(3)", updatable = false)
    private String originalCurrency;

    @Column(name = "fx_rate", nullable = false, precision = 13, scale = 6, updatable = false)
    private BigDecimal fxRate;

    @Column(name = "iof_bps", nullable = false, updatable = false)
    private int iofBps;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ForeignChargeEntity() {
    }

    public ForeignChargeEntity(UUID transactionId, UUID userId, long originalAmountCents, String originalCurrency, BigDecimal fxRate,
                               int iofBps, Instant now) {
        this.transactionId = transactionId;
        this.userId = userId;
        this.originalAmountCents = originalAmountCents;
        this.originalCurrency = originalCurrency;
        this.fxRate = fxRate;
        this.iofBps = iofBps;
        this.createdAt = now;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public long getOriginalAmountCents() {
        return originalAmountCents;
    }

    public String getOriginalCurrency() {
        return originalCurrency;
    }

    public BigDecimal getFxRate() {
        return fxRate;
    }

    public int getIofBps() {
        return iofBps;
    }
}
