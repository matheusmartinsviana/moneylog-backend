package com.moneylog.infra.debt.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.moneylog.core.debt.domain.DebtClosure;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.DebtOrigin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Linha de {@code debts}. Só os fatos gravados (D1 §2.6): o status e o saldo são derivados das quitações (S-41); o que a linha
 * guarda do estado é o encerramento ({@code closure}).
 */
@Entity
@Table(name = "debts")
public class DebtEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "person_id", updatable = false)
    private UUID personId;

    /** Credor livre das "outras dívidas" (V8.04); {@code null} quando há pessoa. */
    @Column(name = "creditor_name", length = 100, updatable = false)
    private String creditorName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private DebtDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private DebtOrigin origin;

    @Column(name = "principal_cents", nullable = false, updatable = false)
    private long principalCents;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)", updatable = false)
    private String currency;

    @Column(length = 200)
    private String description;

    @Column(name = "debt_date", nullable = false, updatable = false)
    private LocalDate debtDate;

    @Column(name = "expected_date")
    private LocalDate expectedDate;

    @Column(name = "origin_transaction_id", updatable = false)
    private UUID originTransactionId;

    /** Categoria de despesa da dívida {@code PAYABLE} que nasceu de consumo (S-137, V4.01); {@code null} nas demais. */
    @Column(name = "category_id", updatable = false)
    private UUID categoryId;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private DebtClosure closure;

    @Column(name = "closure_date")
    private LocalDate closureDate;

    @Column(name = "closure_reason", length = 500)
    private String closureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected DebtEntity() {
    }

    @SuppressWarnings("java:S107")
    public DebtEntity(UUID id, UUID userId, UUID personId, DebtDirection direction, DebtOrigin origin, long principalCents,
                      String currency, String description, LocalDate debtDate, LocalDate expectedDate, UUID originTransactionId,
                      DebtClosure closure, LocalDate closureDate, String closureReason, UUID categoryId, String creditorName, Instant now) {
        this.id = id;
        this.userId = userId;
        this.personId = personId;
        this.direction = direction;
        this.origin = origin;
        this.principalCents = principalCents;
        this.currency = currency;
        this.description = description;
        this.debtDate = debtDate;
        this.expectedDate = expectedDate;
        this.originTransactionId = originTransactionId;
        this.closure = closure;
        this.closureDate = closureDate;
        this.closureReason = closureReason;
        this.categoryId = categoryId;
        this.creditorName = creditorName;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Aplica o que muda numa dívida existente: descrição, previsão e encerramento. A versão sobe a cada gravação. */
    public void apply(String newDescription, LocalDate newExpectedDate, DebtClosure newClosure, LocalDate newClosureDate,
                      String newClosureReason, Instant now) {
        this.description = newDescription;
        this.expectedDate = newExpectedDate;
        this.closure = newClosure;
        this.closureDate = newClosureDate;
        this.closureReason = newClosureReason;
        this.updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plus(1, ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getPersonId() {
        return personId;
    }

    public String getCreditorName() {
        return creditorName;
    }

    public DebtDirection getDirection() {
        return direction;
    }

    public DebtOrigin getOrigin() {
        return origin;
    }

    public long getPrincipalCents() {
        return principalCents;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getDebtDate() {
        return debtDate;
    }

    public LocalDate getExpectedDate() {
        return expectedDate;
    }

    public UUID getOriginTransactionId() {
        return originTransactionId;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public DebtClosure getClosure() {
        return closure;
    }

    public LocalDate getClosureDate() {
        return closureDate;
    }

    public String getClosureReason() {
        return closureReason;
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
