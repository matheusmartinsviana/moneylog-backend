package com.moneylog.infra.card.persistence;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Linha de {@code credit_card_billing_rules}: a regra de fechamento/vencimento que vale a partir de um mês (S-42). */
@Entity
@Table(name = "credit_card_billing_rules")
public class BillingRuleEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "card_id", nullable = false, updatable = false)
    private UUID cardId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "effective_from", nullable = false, length = 7, columnDefinition = "char(7)", updatable = false)
    private String effectiveFrom;

    @Column(name = "closing_day", nullable = false)
    private short closingDay;

    @Column(name = "due_day", nullable = false)
    private short dueDay;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BillingRuleEntity() {
    }

    public BillingRuleEntity(UUID id, UUID userId, UUID cardId, String effectiveFrom, int closingDay, int dueDay, Instant now) {
        this.id = id;
        this.userId = userId;
        this.cardId = cardId;
        this.effectiveFrom = effectiveFrom;
        this.closingDay = (short) closingDay;
        this.dueDay = (short) dueDay;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Só toca {@code updatedAt} se um dos dias mudou. */
    public void apply(int newClosingDay, int newDueDay, Instant now) {
        if (closingDay != newClosingDay || dueDay != newDueDay) {
            this.closingDay = (short) newClosingDay;
            this.dueDay = (short) newDueDay;
            this.updatedAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getCardId() {
        return cardId;
    }

    public String getEffectiveFrom() {
        return effectiveFrom;
    }

    public int getClosingDay() {
        return closingDay;
    }

    public int getDueDay() {
        return dueDay;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
