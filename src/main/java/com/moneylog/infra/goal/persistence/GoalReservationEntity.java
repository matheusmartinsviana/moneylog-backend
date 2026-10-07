package com.moneylog.infra.goal.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Linha de {@code goal_reservations}: só se insere (liberar é uma linha negativa). Não é lançamento do razão. */
@Entity
@Table(name = "goal_reservations")
public class GoalReservationEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "goal_id", nullable = false, updatable = false)
    private UUID goalId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "amount_cents", nullable = false, updatable = false)
    private long amountCents;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)", updatable = false)
    private String currency;

    @Column(name = "reserved_on", nullable = false, updatable = false)
    private LocalDate reservedOn;

    @Column(length = 200, updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Igual a {@code createdAt}: a reserva não é editada (convenção do esquema: toda tabela tem os dois carimbos). */
    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    protected GoalReservationEntity() {
    }

    @SuppressWarnings("java:S107")
    public GoalReservationEntity(UUID id, UUID userId, UUID goalId, UUID accountId, long amountCents, String currency,
                                 LocalDate reservedOn, String note, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.goalId = goalId;
        this.accountId = accountId;
        this.amountCents = amountCents;
        this.currency = currency;
        this.reservedOn = reservedOn;
        this.note = note;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getGoalId() {
        return goalId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getReservedOn() {
        return reservedOn;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
