package com.moneylog.infra.recurrence.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.IntervalUnit;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.TransactionType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "recurring_transactions")
public class RecurrenceEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private TransactionType type;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)", updatable = false)
    private String currency;

    @Column(length = 200)
    private String description;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "card_id")
    private UUID cardId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 16)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private Frequency frequency;

    @Column(name = "interval_count", nullable = false, updatable = false)
    private int intervalCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "interval_unit", length = 16, updatable = false)
    private IntervalUnit intervalUnit;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "max_occurrences", updatable = false)
    private Integer maxOccurrences;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "income_competence_offset_months", nullable = false)
    private int incomeCompetenceOffsetMonths;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected RecurrenceEntity() {
    }

    @SuppressWarnings("java:S107")
    public RecurrenceEntity(UUID id, UUID userId, TransactionType type, long amountCents, String currency, String description,
                            UUID categoryId, UUID accountId, UUID cardId, PaymentMethod paymentMethod, Frequency frequency,
                            int intervalCount, IntervalUnit intervalUnit, LocalDate startDate, LocalDate endDate,
                            Integer maxOccurrences, boolean active, int incomeCompetenceOffsetMonths, Instant now) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.amountCents = amountCents;
        this.currency = currency;
        this.description = description;
        this.categoryId = categoryId;
        this.accountId = accountId;
        this.cardId = cardId;
        this.paymentMethod = paymentMethod;
        this.frequency = frequency;
        this.intervalCount = intervalCount;
        this.intervalUnit = intervalUnit;
        this.startDate = startDate;
        this.endDate = endDate;
        this.maxOccurrences = maxOccurrences;
        this.active = active;
        this.incomeCompetenceOffsetMonths = incomeCompetenceOffsetMonths;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @SuppressWarnings("java:S107")
    public void apply(long newAmountCents, String newDescription, UUID newCategoryId, UUID newAccountId, UUID newCardId,
                      PaymentMethod newMethod, LocalDate newEndDate, boolean newActive, int newIncomeCompetenceOffsetMonths, Instant now) {
        this.amountCents = newAmountCents;
        this.description = newDescription;
        this.categoryId = newCategoryId;
        this.accountId = newAccountId;
        this.cardId = newCardId;
        this.paymentMethod = newMethod;
        this.endDate = newEndDate;
        this.active = newActive;
        this.incomeCompetenceOffsetMonths = newIncomeCompetenceOffsetMonths;
        this.updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plus(1, ChronoUnit.MICROS);
    }

    public int getIncomeCompetenceOffsetMonths() {
        return incomeCompetenceOffsetMonths;
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

    public long getAmountCents() {
        return amountCents;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getCardId() {
        return cardId;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public Frequency getFrequency() {
        return frequency;
    }

    public int getIntervalCount() {
        return intervalCount;
    }

    public IntervalUnit getIntervalUnit() {
        return intervalUnit;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public Integer getMaxOccurrences() {
        return maxOccurrences;
    }

    public boolean isActive() {
        return active;
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
