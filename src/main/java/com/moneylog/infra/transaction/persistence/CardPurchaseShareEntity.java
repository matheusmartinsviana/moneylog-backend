package com.moneylog.infra.transaction.persistence;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.moneylog.core.sk.provenance.Provenance;
import com.moneylog.core.transaction.domain.SplitMode;
import com.moneylog.infra.persistence.ProvenanceColumns;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "card_purchase_shares")
public class CardPurchaseShareEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

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

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)", updatable = false)
    private String currency;

    @Embedded
    private ProvenanceColumns provenance = ProvenanceColumns.of(null);

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CardPurchaseShareEntity() {
    }

    public void applyProvenance(Provenance newProvenance) {
        this.provenance = ProvenanceColumns.of(newProvenance);
    }

    public Provenance provenance() {
        return provenance.toDomain();
    }

    @SuppressWarnings("java:S107")
    public CardPurchaseShareEntity(UUID id, UUID userId, UUID transactionId, int position, SplitMode splitMode, UUID personId,
                                   Integer bps, Long amountCents, String currency, Instant now) {
        this.id = id;
        this.userId = userId;
        this.transactionId = transactionId;
        this.position = (short) position;
        this.splitMode = splitMode;
        this.personId = personId;
        this.bps = bps;
        this.amountCents = amountCents;
        this.currency = currency;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
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

    public String getCurrency() {
        return currency;
    }
}
