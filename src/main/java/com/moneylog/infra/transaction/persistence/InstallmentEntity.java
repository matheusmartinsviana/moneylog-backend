package com.moneylog.infra.transaction.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "transaction_installments")
public class InstallmentEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(nullable = false, updatable = false)
    private short number;

    @Column(name = "total_installments", nullable = false, updatable = false)
    private short totalInstallments;

    @Column(name = "amount_cents", nullable = false, updatable = false)
    private long amountCents;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)", updatable = false)
    private String currency;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 7, columnDefinition = "char(7)", updatable = false)
    private String competence;

    @Column(name = "effective_date", nullable = false, updatable = false)
    private LocalDate effectiveDate;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "late_entry", nullable = false)
    private boolean lateEntry;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected InstallmentEntity() {
    }

    @SuppressWarnings("java:S107")
    public InstallmentEntity(UUID id, UUID userId, UUID transactionId, int number, int totalInstallments, long amountCents,
                             String currency, String competence, LocalDate effectiveDate, UUID invoiceId, boolean lateEntry,
                             Instant canceledAt, Instant now) {
        this.id = id;
        this.userId = userId;
        this.transactionId = transactionId;
        this.number = (short) number;
        this.totalInstallments = (short) totalInstallments;
        this.amountCents = amountCents;
        this.currency = currency;
        this.competence = competence;
        this.effectiveDate = effectiveDate;
        this.invoiceId = invoiceId;
        this.lateEntry = lateEntry;
        this.canceledAt = canceledAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void apply(UUID newInvoiceId, Instant newCanceledAt, Instant now) {
        boolean changed = !invoiceId.equals(newInvoiceId) || !java.util.Objects.equals(canceledAt, newCanceledAt);
        if (changed) {
            this.invoiceId = newInvoiceId;
            this.canceledAt = newCanceledAt;
            this.updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plus(1, java.time.temporal.ChronoUnit.MICROS);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public int getNumber() {
        return number;
    }

    public int getTotalInstallments() {
        return totalInstallments;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCompetence() {
        return competence;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public boolean isLateEntry() {
        return lateEntry;
    }

    public Instant getCanceledAt() {
        return canceledAt;
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
