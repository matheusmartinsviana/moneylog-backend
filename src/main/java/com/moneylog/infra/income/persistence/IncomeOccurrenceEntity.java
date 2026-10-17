package com.moneylog.infra.income.persistence;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.moneylog.core.income.domain.PayrollKind;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "income_profile_occurrences")
public class IncomeOccurrenceEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "profile_id", nullable = false, updatable = false)
    private UUID profileId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 7, columnDefinition = "char(7)", updatable = false)
    private String competence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private PayrollKind kind;

    @Column(name = "recurrence_id", nullable = false, updatable = false)
    private UUID recurrenceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IncomeOccurrenceEntity() {
    }

    public IncomeOccurrenceEntity(UUID id, UUID userId, UUID profileId, String competence, PayrollKind kind, UUID recurrenceId,
                                  Instant now) {
        this.id = id;
        this.userId = userId;
        this.profileId = profileId;
        this.competence = competence;
        this.kind = kind;
        this.recurrenceId = recurrenceId;
        this.createdAt = now;
    }

    public UUID getProfileId() {
        return profileId;
    }

    public String getCompetence() {
        return competence;
    }

    public PayrollKind getKind() {
        return kind;
    }

    public UUID getRecurrenceId() {
        return recurrenceId;
    }
}
