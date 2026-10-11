package com.moneylog.infra.goal.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.moneylog.core.goal.domain.GoalDirection;
import com.moneylog.core.goal.domain.GoalKind;
import com.moneylog.core.goal.domain.GoalMetric;
import com.moneylog.core.goal.domain.GoalPriority;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "goals")
public class GoalEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(length = 100)
    private String name;

    @Column(name = "target_cents", nullable = false)
    private long targetCents;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)", updatable = false)
    private String currency;

    @Column
    private LocalDate deadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private GoalPriority priority;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private boolean archived;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private GoalKind kind = GoalKind.SAVINGS;

    @Column(name = "baseline_cents")
    private Long baselineCents;

    @Column(name = "baseline_at")
    private Instant baselineAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 24, updatable = false)
    private GoalMetric metric;

    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private GoalDirection direction;

    @Column(name = "plan_note", length = 500)
    private String planNote;

    @Column(nullable = false)
    private boolean featured;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected GoalEntity() {
    }

    @SuppressWarnings("java:S107")
    public GoalEntity(UUID id, UUID userId, String name, long targetCents, String currency, LocalDate deadline, GoalPriority priority,
                      String description, boolean archived, GoalKind kind, Long baselineCents, Instant baselineAt, GoalMetric metric,
                      GoalDirection direction, String planNote, boolean featured, Instant now) {
        this.featured = featured;
        this.metric = metric;
        this.direction = direction;
        this.planNote = planNote;
        this.kind = kind;
        this.baselineCents = baselineCents;
        this.baselineAt = baselineAt;
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.targetCents = targetCents;
        this.currency = currency;
        this.deadline = deadline;
        this.priority = priority;
        this.description = description;
        this.archived = archived;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @SuppressWarnings("java:S107")
    public void apply(String newName, long newTargetCents, LocalDate newDeadline, GoalPriority newPriority, String newDescription,
                      boolean newArchived, Long newBaselineCents, Instant newBaselineAt, GoalDirection newDirection, String newPlanNote,
                      boolean newFeatured, Instant now) {
        this.featured = newFeatured;
        this.direction = newDirection;
        this.planNote = newPlanNote;
        this.baselineCents = newBaselineCents;
        this.baselineAt = newBaselineAt;
        this.name = newName;
        this.targetCents = newTargetCents;
        this.deadline = newDeadline;
        this.priority = newPriority;
        this.description = newDescription;
        this.archived = newArchived;
        this.updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plus(1, ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public long getTargetCents() {
        return targetCents;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getDeadline() {
        return deadline;
    }

    public GoalPriority getPriority() {
        return priority;
    }

    public String getDescription() {
        return description;
    }

    public GoalKind getKind() {
        return kind;
    }

    public GoalMetric getMetric() {
        return metric;
    }

    public GoalDirection getDirection() {
        return direction;
    }

    public String getPlanNote() {
        return planNote;
    }

    public Long getBaselineCents() {
        return baselineCents;
    }

    public Instant getBaselineAt() {
        return baselineAt;
    }

    public boolean isFeatured() {
        return featured;
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
