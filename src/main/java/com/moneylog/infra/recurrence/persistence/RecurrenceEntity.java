package com.moneylog.infra.recurrence.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.IntervalUnit;
import com.moneylog.core.recurrence.domain.RecurrenceScope;
import com.moneylog.core.sk.provenance.Provenance;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.infra.persistence.ProvenanceColumns;
import com.moneylog.core.transaction.domain.TransactionNature;
import com.moneylog.core.transaction.domain.TransactionType;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private TransactionNature nature;

    @Column(name = "original_amount_cents")
    private Long originalAmountCents;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "original_currency", length = 3, columnDefinition = "char(3)")
    private String originalCurrency;

    @Column(name = "reference_fx_rate", precision = 13, scale = 6)
    private BigDecimal referenceFxRate;

    @Column(name = "iof_bps")
    private Integer iofBps;

    @Column(name = "is_subscription", nullable = false)
    private boolean subscription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RecurrenceScope scope = RecurrenceScope.PERSONAL;

    @Embedded
    private ProvenanceColumns provenance = ProvenanceColumns.of(null);

    @Column(length = 60)
    private String channel;

    @Column(name = "overlap_group", length = 60)
    private String overlapGroup;

    @Column(name = "last_used_on")
    private LocalDate lastUsedOn;

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
                            Integer maxOccurrences, boolean active, int incomeCompetenceOffsetMonths, TransactionNature nature,
                            Instant now) {
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
        this.nature = nature;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @SuppressWarnings("java:S107")
    public void apply(long newAmountCents, String newDescription, UUID newCategoryId, UUID newAccountId, UUID newCardId,
                      PaymentMethod newMethod, LocalDate newEndDate, boolean newActive, int newIncomeCompetenceOffsetMonths,
                      TransactionNature newNature, Instant now) {
        this.amountCents = newAmountCents;
        this.description = newDescription;
        this.categoryId = newCategoryId;
        this.accountId = newAccountId;
        this.cardId = newCardId;
        this.paymentMethod = newMethod;
        this.endDate = newEndDate;
        this.active = newActive;
        this.incomeCompetenceOffsetMonths = newIncomeCompetenceOffsetMonths;
        this.nature = newNature;
        this.updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plus(1, ChronoUnit.MICROS);
    }

    public void applyProvenance(Provenance newProvenance) {
        this.provenance = ProvenanceColumns.of(newProvenance);
    }

    public Provenance provenance() {
        return provenance.toDomain();
    }

    public void applyForeignAndSubscription(Long newOriginalAmountCents, String newOriginalCurrency, BigDecimal newReferenceFxRate,
                                            Integer newIofBps, boolean newSubscription, RecurrenceScope newScope, String newChannel,
                                            String newOverlapGroup, LocalDate newLastUsedOn) {
        this.originalAmountCents = newOriginalAmountCents;
        this.originalCurrency = newOriginalCurrency;
        this.referenceFxRate = newReferenceFxRate;
        this.iofBps = newIofBps;
        this.subscription = newSubscription;
        this.scope = newScope;
        this.channel = newChannel;
        this.overlapGroup = newOverlapGroup;
        this.lastUsedOn = newLastUsedOn;
    }

    public Long getOriginalAmountCents() {
        return originalAmountCents;
    }

    public String getOriginalCurrency() {
        return originalCurrency;
    }

    public BigDecimal getReferenceFxRate() {
        return referenceFxRate;
    }

    public Integer getIofBps() {
        return iofBps;
    }

    public boolean isSubscription() {
        return subscription;
    }

    public RecurrenceScope getScope() {
        return scope;
    }

    public String getChannel() {
        return channel;
    }

    public String getOverlapGroup() {
        return overlapGroup;
    }

    public LocalDate getLastUsedOn() {
        return lastUsedOn;
    }

    public TransactionNature getNature() {
        return nature;
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
