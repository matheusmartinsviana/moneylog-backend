package com.moneylog.infra.card.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.moneylog.core.card.domain.CardStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Linha de {@code credit_cards}. Não tem número de cartão, CVV, bandeira nem dia de fechamento/vencimento: os dias são
 * da regra mais recente em {@code credit_card_billing_rules} (única fonte, D1 §2.5.1).
 */
@Entity
@Table(name = "credit_cards")
public class CardEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String institution;

    @Column(name = "limit_cents", nullable = false)
    private long limitCents;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)", updatable = false)
    private String currency;

    @Column(name = "default_payment_account_id")
    private UUID defaultPaymentAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CardStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected CardEntity() {
    }

    public CardEntity(UUID id, UUID userId, String name, String institution, long limitCents, String currency,
                      UUID defaultPaymentAccountId, CardStatus status, Instant now) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.institution = institution;
        this.limitCents = limitCents;
        this.currency = currency;
        this.defaultPaymentAccountId = defaultPaymentAccountId;
        this.status = status;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Aplica o estado do domínio. {@code updated_at} sobe a cada gravação (mesmo com o relógio parado): a linha sempre
     * fica "suja" e o JPA sempre incrementa a versão, então trocar só as regras de fechamento também cria a versão
     * seguinte do cartão (lock otimista do {@code PUT}).
     */
    public void apply(String newName, String newInstitution, long newLimitCents, UUID newDefaultPaymentAccountId,
                      CardStatus newStatus, Instant now) {
        this.name = newName;
        this.institution = newInstitution;
        this.limitCents = newLimitCents;
        this.defaultPaymentAccountId = newDefaultPaymentAccountId;
        this.status = Objects.requireNonNull(newStatus, "status");
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

    public String getInstitution() {
        return institution;
    }

    public long getLimitCents() {
        return limitCents;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getDefaultPaymentAccountId() {
        return defaultPaymentAccountId;
    }

    public CardStatus getStatus() {
        return status;
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
