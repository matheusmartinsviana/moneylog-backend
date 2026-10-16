package com.moneylog.infra.asset.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.moneylog.core.asset.domain.AssetAvailability;
import com.moneylog.core.asset.domain.AssetKind;
import com.moneylog.core.sk.provenance.Provenance;
import com.moneylog.infra.persistence.ProvenanceColumns;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "assets")
public class AssetEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private AssetKind kind;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String employer;

    @Column(name = "balance_cents", nullable = false)
    private long balanceCents;

    @Column(name = "balance_date", nullable = false)
    private LocalDate balanceDate;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)", updatable = false)
    private String currency;

    @Column(name = "monthly_deposit_cents")
    private Long monthlyDepositCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AssetAvailability availability;

    @Column(name = "counts_as_reserve", nullable = false)
    private boolean countsAsReserve;

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

    protected AssetEntity() {
    }

    @SuppressWarnings("java:S107")
    public AssetEntity(UUID id, UUID userId, AssetKind kind, String name, String employer, long balanceCents, LocalDate balanceDate,
                       String currency, Long monthlyDepositCents, AssetAvailability availability, boolean countsAsReserve,
                       boolean archived, Instant now) {
        this.id = id;
        this.userId = userId;
        this.currency = currency;
        this.createdAt = now;
        apply(kind, name, employer, balanceCents, balanceDate, monthlyDepositCents, availability, countsAsReserve, archived, now);
    }

    @SuppressWarnings("java:S107")
    public void apply(AssetKind newKind, String newName, String newEmployer, long newBalanceCents, LocalDate newBalanceDate,
                      Long newMonthlyDepositCents, AssetAvailability newAvailability, boolean newCountsAsReserve, boolean newArchived,
                      Instant now) {
        this.kind = newKind;
        this.name = newName;
        this.employer = newEmployer;
        this.balanceCents = newBalanceCents;
        this.balanceDate = newBalanceDate;
        this.monthlyDepositCents = newMonthlyDepositCents;
        this.availability = newAvailability;
        this.countsAsReserve = newCountsAsReserve;
        this.archived = newArchived;
        this.updatedAt = updatedAt == null || now.isAfter(updatedAt) ? now : updatedAt.plus(1, ChronoUnit.MICROS);
    }

    public void applyProvenance(Provenance newProvenance) {
        this.provenance = ProvenanceColumns.of(newProvenance);
    }

    public Provenance provenance() {
        return provenance.toDomain();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public AssetKind getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    public String getEmployer() {
        return employer;
    }

    public long getBalanceCents() {
        return balanceCents;
    }

    public LocalDate getBalanceDate() {
        return balanceDate;
    }

    public String getCurrency() {
        return currency;
    }

    public Long getMonthlyDepositCents() {
        return monthlyDepositCents;
    }

    public AssetAvailability getAvailability() {
        return availability;
    }

    public boolean isCountsAsReserve() {
        return countsAsReserve;
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
