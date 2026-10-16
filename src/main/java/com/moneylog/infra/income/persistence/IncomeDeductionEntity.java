package com.moneylog.infra.income.persistence;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.income.domain.DeductionKind;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "income_profile_deductions")
public class IncomeDeductionEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "profile_id", nullable = false, updatable = false)
    private UUID profileId;

    @Column(nullable = false, updatable = false)
    private short position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private DeductionKind kind;

    @Column(nullable = false, length = 100, updatable = false)
    private String label;

    @Column(name = "amount_cents", updatable = false)
    private Long amountCents;

    @Column(name = "percent_bps", updatable = false)
    private Integer percentBps;

    @Column(name = "starts_on", updatable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", updatable = false)
    private LocalDate endsOn;

    @Column(name = "debt_id", updatable = false)
    private UUID debtId;

    @Column(name = "interest_cents", updatable = false)
    private Long interestCents;

    protected IncomeDeductionEntity() {
    }

    @SuppressWarnings("java:S107")
    public IncomeDeductionEntity(UUID id, UUID userId, UUID profileId, int position, DeductionKind kind, String label,
                                 Long amountCents, Integer percentBps, LocalDate startsOn, LocalDate endsOn, UUID debtId,
                                 Long interestCents) {
        this.interestCents = interestCents;
        this.id = id;
        this.userId = userId;
        this.profileId = profileId;
        this.position = (short) position;
        this.kind = kind;
        this.label = label;
        this.amountCents = amountCents;
        this.percentBps = percentBps;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.debtId = debtId;
    }

    public UUID getProfileId() {
        return profileId;
    }

    public int getPosition() {
        return position;
    }

    public DeductionKind getKind() {
        return kind;
    }

    public String getLabel() {
        return label;
    }

    public Long getAmountCents() {
        return amountCents;
    }

    public Integer getPercentBps() {
        return percentBps;
    }

    public LocalDate getStartsOn() {
        return startsOn;
    }

    public LocalDate getEndsOn() {
        return endsOn;
    }

    public Long getInterestCents() {
        return interestCents;
    }

    public UUID getDebtId() {
        return debtId;
    }
}
