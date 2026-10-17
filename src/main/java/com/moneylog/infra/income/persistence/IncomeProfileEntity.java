package com.moneylog.infra.income.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.moneylog.core.sk.provenance.Provenance;
import com.moneylog.infra.persistence.ProvenanceColumns;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "income_profiles")
public class IncomeProfileEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 100)
    private String source;

    @Column(name = "gross_cents", nullable = false)
    private long grossCents;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)", updatable = false)
    private String currency;

    @Column(nullable = false)
    private short dependents;

    @Column(name = "advance_day")
    private Short advanceDay;

    @Column(name = "advance_bps")
    private Integer advanceBps;

    @Column(name = "balance_day", nullable = false)
    private short balanceDay;

    @Column(name = "thirteenth_enabled", nullable = false)
    private boolean thirteenthEnabled;

    @Column(name = "thirteenth_first_month", nullable = false)
    private short thirteenthFirstMonth;

    @Column(nullable = false)
    private boolean archived;

    @Embedded
    private ProvenanceColumns provenance = ProvenanceColumns.of(null);

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected IncomeProfileEntity() {
    }

    @SuppressWarnings("java:S107")
    public void applyProvenance(Provenance newProvenance) {
        this.provenance = ProvenanceColumns.of(newProvenance);
    }

    public Provenance provenance() {
        return provenance.toDomain();
    }

    public IncomeProfileEntity(UUID id, UUID userId, String source, long grossCents, String currency, int dependents,
                               Integer advanceDay, Integer advanceBps, int balanceDay, boolean thirteenthEnabled,
                               int thirteenthFirstMonth, boolean archived, Instant now) {
        this.id = id;
        this.userId = userId;
        this.currency = currency;
        this.createdAt = now;
        this.updatedAt = now;
        apply(source, grossCents, dependents, advanceDay, advanceBps, balanceDay, thirteenthEnabled, thirteenthFirstMonth, archived, now);
        this.updatedAt = now;
    }

    @SuppressWarnings("java:S107")
    public void apply(String newSource, long newGrossCents, int newDependents, Integer newAdvanceDay, Integer newAdvanceBps,
                      int newBalanceDay, boolean newThirteenthEnabled, int newThirteenthFirstMonth, boolean newArchived, Instant now) {
        this.source = newSource;
        this.grossCents = newGrossCents;
        this.dependents = (short) newDependents;
        this.advanceDay = newAdvanceDay == null ? null : newAdvanceDay.shortValue();
        this.advanceBps = newAdvanceBps;
        this.balanceDay = (short) newBalanceDay;
        this.thirteenthEnabled = newThirteenthEnabled;
        this.thirteenthFirstMonth = (short) newThirteenthFirstMonth;
        this.archived = newArchived;
        this.updatedAt = updatedAt == null || now.isAfter(updatedAt) ? now : updatedAt.plus(1, ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getSource() {
        return source;
    }

    public long getGrossCents() {
        return grossCents;
    }

    public String getCurrency() {
        return currency;
    }

    public int getDependents() {
        return dependents;
    }

    public Integer getAdvanceDay() {
        return advanceDay == null ? null : advanceDay.intValue();
    }

    public Integer getAdvanceBps() {
        return advanceBps;
    }

    public int getBalanceDay() {
        return balanceDay;
    }

    public boolean isThirteenthEnabled() {
        return thirteenthEnabled;
    }

    public int getThirteenthFirstMonth() {
        return thirteenthFirstMonth;
    }

    public boolean isArchived() {
        return archived;
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
