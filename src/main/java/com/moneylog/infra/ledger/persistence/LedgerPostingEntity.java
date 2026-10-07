package com.moneylog.infra.ledger.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.PostingSourceType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Uma linha de {@code ledger_postings}: um posting com origem e vigência (D1 §1.1). */
@Entity
@Table(name = "ledger_postings")
public class LedgerPostingEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private PostingSourceType sourceType;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "installment_id")
    private UUID installmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "book_type", nullable = false, length = 16)
    private BookType bookType;

    @Column(name = "book_id")
    private UUID bookId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 7, columnDefinition = "char(7)")
    private String competence;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @Column(name = "deactivation_reason", length = 500)
    private String deactivationReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LedgerPostingEntity() {
    }

    public LedgerPostingEntity(UUID id, UUID userId, PostingSourceType sourceType, UUID sourceId, UUID transactionId,
                               UUID installmentId, BookType bookType, UUID bookId, String competence, LocalDate effectiveDate,
                               long amountCents, String currency, boolean active, Instant now) {
        this.id = id;
        this.userId = userId;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.transactionId = transactionId;
        this.installmentId = installmentId;
        this.bookType = bookType;
        this.bookId = bookId;
        this.competence = competence;
        this.effectiveDate = effectiveDate;
        this.amountCents = amountCents;
        this.currency = currency;
        this.active = active;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public PostingSourceType getSourceType() {
        return sourceType;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getInstallmentId() {
        return installmentId;
    }

    public BookType getBookType() {
        return bookType;
    }

    public UUID getBookId() {
        return bookId;
    }

    public String getCompetence() {
        return competence;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getDeactivatedAt() {
        return deactivatedAt;
    }

    public String getDeactivationReason() {
        return deactivationReason;
    }
}
