package com.moneylog.core.card.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;

public record InvoiceItem(UUID transactionId, UUID installmentId, InvoiceItemKind kind, Money amount, LocalDate date,
                          LocalDate invoiceClosingDate, boolean lateEntry, boolean active) {

    public InvoiceItem {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(invoiceClosingDate, "invoiceClosingDate");
    }

    public Money signedAmount() {
        return kind == InvoiceItemKind.INSTALLMENT ? amount : amount.negate();
    }

    public InvoiceItem deactivate() {
        return new InvoiceItem(transactionId, installmentId, kind, amount, date, invoiceClosingDate, lateEntry, false);
    }
}
