package com.moneylog.infra.account.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.account.domain.AccountType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "accounts")
public class AccountEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountType type;

    @Column(length = 100)
    private String institution;

    @Column(name = "opening_balance_cents", nullable = false)
    private long openingBalanceCents;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)", updatable = false)
    private String currency;

    @Column(name = "opening_date", nullable = false)
    private LocalDate openingDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected AccountEntity() {
    }

    public AccountEntity(UUID id, UUID userId, String name, AccountType type, String institution, long openingBalanceCents,
                         String currency, LocalDate openingDate, AccountStatus status, Instant now) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.type = type;
        this.institution = institution;
        this.openingBalanceCents = openingBalanceCents;
        this.currency = currency;
        this.openingDate = openingDate;
        this.status = status;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Aplica o estado editável do domínio; só toca {@code updatedAt} se algo mudou (o lock otimista só avança com mudança). */
    public void apply(String newName, AccountType newType, String newInstitution, long newOpeningBalanceCents,
                      LocalDate newOpeningDate, AccountStatus newStatus, Instant now) {
        boolean changed = !name.equals(newName) || type != newType || !java.util.Objects.equals(institution, newInstitution)
                || openingBalanceCents != newOpeningBalanceCents || !openingDate.equals(newOpeningDate) || status != newStatus;
        if (changed) {
            this.name = newName;
            this.type = newType;
            this.institution = newInstitution;
            this.openingBalanceCents = newOpeningBalanceCents;
            this.openingDate = newOpeningDate;
            this.status = newStatus;
            this.updatedAt = now;
        }
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

    public AccountType getType() {
        return type;
    }

    public String getInstitution() {
        return institution;
    }

    public long getOpeningBalanceCents() {
        return openingBalanceCents;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getOpeningDate() {
        return openingDate;
    }

    public AccountStatus getStatus() {
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
