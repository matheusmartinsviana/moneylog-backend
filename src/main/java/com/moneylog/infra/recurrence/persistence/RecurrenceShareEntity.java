package com.moneylog.infra.recurrence.persistence;

import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.sk.provenance.Provenance;
import com.moneylog.core.transaction.domain.SplitMode;
import com.moneylog.infra.persistence.ProvenanceColumns;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "recurrence_shares")
public class RecurrenceShareEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "recurrence_id", nullable = false, updatable = false)
    private UUID recurrenceId;

    @Column(nullable = false, updatable = false)
    private short position;

    @Enumerated(EnumType.STRING)
    @Column(name = "split_mode", nullable = false, updatable = false, length = 8)
    private SplitMode splitMode;

    @Column(name = "person_id", updatable = false)
    private UUID personId;

    @Column(updatable = false)
    private Integer bps;

    @Column(name = "amount_cents", updatable = false)
    private Long amountCents;

    @Embedded
    private ProvenanceColumns provenance = ProvenanceColumns.of(null);

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RecurrenceShareEntity() {
    }

    public void applyProvenance(Provenance newProvenance) {
        this.provenance = ProvenanceColumns.of(newProvenance);
    }

    public Provenance provenance() {
        return provenance.toDomain();
    }

    @SuppressWarnings("java:S107")
    public RecurrenceShareEntity(UUID id, UUID userId, UUID recurrenceId, int position, SplitMode splitMode, UUID personId, Integer bps,
                                 Long amountCents, Instant now) {
        this.id = id;
        this.userId = userId;
        this.recurrenceId = recurrenceId;
        this.position = (short) position;
        this.splitMode = splitMode;
        this.personId = personId;
        this.bps = bps;
        this.amountCents = amountCents;
        this.createdAt = now;
    }

    public UUID getRecurrenceId() {
        return recurrenceId;
    }

    public int getPosition() {
        return position;
    }

    public SplitMode getSplitMode() {
        return splitMode;
    }

    public UUID getPersonId() {
        return personId;
    }

    public Integer getBps() {
        return bps;
    }

    public Long getAmountCents() {
        return amountCents;
    }
}
