package com.moneylog.core.card.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.card.exception.CardExceptions.InvalidInstallmentException;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public final class TransactionInstallment {

    private final UUID id;
    private final UUID transactionId;
    private final int number;
    private final int total;
    private final Money amount;
    private final LocalDate effectiveDate;
    private final LocalDate invoiceClosingDate;
    private final boolean lateEntry;
    private final Instant canceledAt;

    public TransactionInstallment(UUID id, UUID transactionId, int number, int total, Money amount, LocalDate effectiveDate,
                                  LocalDate invoiceClosingDate, boolean lateEntry, Instant canceledAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        if (total < 1 || number < 1 || number > total) {
            throw new InvalidInstallmentException(number + "/" + total);
        }
        this.number = number;
        this.total = total;
        this.amount = Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new InvalidInstallmentException("valor não positivo");
        }
        this.effectiveDate = Objects.requireNonNull(effectiveDate, "effectiveDate");
        this.invoiceClosingDate = Objects.requireNonNull(invoiceClosingDate, "invoiceClosingDate");
        this.lateEntry = lateEntry;
        this.canceledAt = canceledAt;
    }

    public static UUID idFor(UUID transactionId, int number) {
        return UUID.nameUUIDFromBytes((transactionId + "#" + number).getBytes(StandardCharsets.UTF_8));
    }

    TransactionInstallment withInvoiceClosingDate(LocalDate newInvoiceClosingDate) {
        return new TransactionInstallment(id, transactionId, number, total, amount, effectiveDate,
                Objects.requireNonNull(newInvoiceClosingDate, "newInvoiceClosingDate"), lateEntry, canceledAt);
    }

    public TransactionInstallment cancel(Instant now) {
        Objects.requireNonNull(now, "now");
        return isCanceled() ? this
                : new TransactionInstallment(id, transactionId, number, total, amount, effectiveDate, invoiceClosingDate, lateEntry, now);
    }

    public InstallmentStatus statusFor(InvoiceStatus invoiceStatus) {
        if (isCanceled()) {
            return InstallmentStatus.CANCELED;
        }
        return switch (Objects.requireNonNull(invoiceStatus, "invoiceStatus")) {
            case PAID -> InstallmentStatus.PAID;
            case OVERDUE -> InstallmentStatus.OVERDUE;
            default -> InstallmentStatus.PENDING;
        };
    }

    public boolean isCanceled() {
        return canceledAt != null;
    }

    public UUID id() {
        return id;
    }

    public UUID transactionId() {
        return transactionId;
    }

    public int number() {
        return number;
    }

    public int total() {
        return total;
    }

    public Money amount() {
        return amount;
    }

    public LocalDate effectiveDate() {
        return effectiveDate;
    }

    public Competence competence() {
        return Competence.from(effectiveDate);
    }

    public LocalDate invoiceClosingDate() {
        return invoiceClosingDate;
    }

    public boolean lateEntry() {
        return lateEntry;
    }

    public Instant canceledAt() {
        return canceledAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TransactionInstallment other && id.equals(other.id) && Objects.equals(canceledAt, other.canceledAt);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Installment[" + number + "/" + total + ", " + amount + ", " + effectiveDate + ", invoice " + invoiceClosingDate
                + (isCanceled() ? ", CANCELED" : "") + "]";
    }
}
