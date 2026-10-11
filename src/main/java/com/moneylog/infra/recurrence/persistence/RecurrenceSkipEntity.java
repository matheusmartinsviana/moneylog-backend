package com.moneylog.infra.recurrence.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "recurrence_skips")
public class RecurrenceSkipEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "recurrence_id", nullable = false, updatable = false)
    private UUID recurrenceId;

    @Column(name = "occurrence_date", nullable = false, updatable = false)
    private LocalDate occurrenceDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RecurrenceSkipEntity() {
    }

    public RecurrenceSkipEntity(UUID id, UUID userId, UUID recurrenceId, LocalDate occurrenceDate, Instant now) {
        this.id = id;
        this.userId = userId;
        this.recurrenceId = recurrenceId;
        this.occurrenceDate = occurrenceDate;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getRecurrenceId() {
        return recurrenceId;
    }

    public LocalDate getOccurrenceDate() {
        return occurrenceDate;
    }
}
