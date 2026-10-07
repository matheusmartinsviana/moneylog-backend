package com.moneylog.infra.goal.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.moneylog.core.goal.domain.GoalPriority;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Linha de {@code goals}. Toda gravação (inclusive uma reserva nova) sobe {@code updatedAt} e, com ele, a versão. */
@Entity
@Table(name = "goals")
public class GoalEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 100)
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
                      String description, boolean archived, Instant now) {
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

    /** Aplica o estado do domínio. {@code updatedAt} sempre avança, então a versão sobe a cada gravação (também ao reservar). */
    @SuppressWarnings("java:S107")
    public void apply(String newName, long newTargetCents, LocalDate newDeadline, GoalPriority newPriority, String newDescription,
                      boolean newArchived, Instant now) {
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
