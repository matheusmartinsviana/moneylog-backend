package com.moneylog.infra.transaction.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.TransactionSource;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "transactions")
public class TransactionEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionStatus status;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)", updatable = false)
    private String currency;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(length = 200)
    private String description;

    @Column(columnDefinition = "text")
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 16)
    private PaymentMethod paymentMethod;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "counterpart_account_id")
    private UUID counterpartAccountId;

    @Column(name = "card_id", updatable = false)
    private UUID cardId;

    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Column(name = "late_entry", nullable = false)
    private boolean lateEntry;

    @Column(name = "installments", updatable = false)
    private Short installmentCount;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "refund_of_id")
    private UUID refundOfId;

    @Column(name = "debt_id", updatable = false)
    private UUID debtId;

    @Column(length = 16)
    private String direction;

    @Column(name = "adjustment_kind", length = 16)
    private String adjustmentKind;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionSource source;

    @Column(name = "import_batch_id")
    private UUID importBatchId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dedup_key", length = 64, columnDefinition = "char(64)")
    private String dedupKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "original_dedup_key", length = 64, columnDefinition = "char(64)")
    private String originalDedupKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "income_competence", length = 7, columnDefinition = "char(7)")
    private String incomeCompetence;

    @Column(name = "recurrence_id")
    private UUID recurrenceId;

    @Column(name = "occurrence_date")
    private LocalDate occurrenceDate;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected TransactionEntity() {
    }

    @SuppressWarnings("java:S107")
    public TransactionEntity(UUID id, UUID userId, TransactionType type, TransactionStatus status, long amountCents,
                             String currency, LocalDate transactionDate, String description, String notes,
                             PaymentMethod paymentMethod, UUID accountId, UUID counterpartAccountId, UUID cardId,
                             UUID invoiceId, boolean lateEntry, Integer installmentCount, UUID categoryId,
                             UUID refundOfId, UUID debtId, String direction, String adjustmentKind, String reason, TransactionSource source,
                             UUID importBatchId, String dedupKey, String originalDedupKey, UUID recurrenceId,
                             LocalDate occurrenceDate, String voidReason, Instant voidedAt, String incomeCompetence, Instant now) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.status = status;
        this.amountCents = amountCents;
        this.currency = currency;
        this.transactionDate = transactionDate;
        this.description = description;
        this.notes = notes;
        this.paymentMethod = paymentMethod;
        this.accountId = accountId;
        this.counterpartAccountId = counterpartAccountId;
        this.cardId = cardId;
        this.invoiceId = invoiceId;
        this.lateEntry = lateEntry;
        this.installmentCount = installmentCount == null ? null : installmentCount.shortValue();
        this.categoryId = categoryId;
        this.refundOfId = refundOfId;
        this.debtId = debtId;
        this.direction = direction;
        this.adjustmentKind = adjustmentKind;
        this.reason = reason;
        this.source = source;
        this.importBatchId = importBatchId;
        this.dedupKey = dedupKey;
        this.originalDedupKey = originalDedupKey;
        this.recurrenceId = recurrenceId;
        this.occurrenceDate = occurrenceDate;
        this.voidReason = voidReason;
        this.voidedAt = voidedAt;
        this.incomeCompetence = incomeCompetence;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @SuppressWarnings("java:S107")
    public void apply(TransactionStatus newStatus, long newAmountCents, LocalDate newDate, String newDescription, String newNotes,
                      PaymentMethod newPaymentMethod, UUID newAccountId, UUID newCounterpartAccountId, UUID newInvoiceId,
                      boolean newLateEntry, UUID newCategoryId, UUID newRefundOfId, String newDirection, String newAdjustmentKind, String newReason, String newVoidReason,
                      Instant newVoidedAt, String newIncomeCompetence, Instant now) {
        this.status = newStatus;
        this.amountCents = newAmountCents;
        this.transactionDate = newDate;
        this.description = newDescription;
        this.notes = newNotes;
        this.paymentMethod = newPaymentMethod;
        this.accountId = newAccountId;
        this.counterpartAccountId = newCounterpartAccountId;
        this.invoiceId = newInvoiceId;
        this.lateEntry = newLateEntry;
        this.categoryId = newCategoryId;
        this.refundOfId = newRefundOfId;
        this.direction = newDirection;
        this.adjustmentKind = newAdjustmentKind;
        this.reason = newReason;
        this.voidReason = newVoidReason;
        this.voidedAt = newVoidedAt;
        this.incomeCompetence = newIncomeCompetence;
        this.updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plus(1, java.time.temporal.ChronoUnit.MICROS);
    }

    public String getIncomeCompetence() {
        return incomeCompetence;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public TransactionType getType() {
        return type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getTransactionDate() {
        return transactionDate;
    }

    public String getDescription() {
        return description;
    }

    public String getNotes() {
        return notes;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getCounterpartAccountId() {
        return counterpartAccountId;
    }

    public UUID getCardId() {
        return cardId;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public boolean isLateEntry() {
        return lateEntry;
    }

    public Integer getInstallmentCount() {
        return installmentCount == null ? null : installmentCount.intValue();
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public UUID getRefundOfId() {
        return refundOfId;
    }

    public UUID getDebtId() {
        return debtId;
    }

    public String getDirection() {
        return direction;
    }

    public String getAdjustmentKind() {
        return adjustmentKind;
    }

    public String getReason() {
        return reason;
    }

    public TransactionSource getSource() {
        return source;
    }

    public UUID getImportBatchId() {
        return importBatchId;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public String getOriginalDedupKey() {
        return originalDedupKey;
    }

    public UUID getRecurrenceId() {
        return recurrenceId;
    }

    public LocalDate getOccurrenceDate() {
        return occurrenceDate;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public Instant getVoidedAt() {
        return voidedAt;
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
