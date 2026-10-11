package com.moneylog.core.card.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;

public record InvoicePayment(UUID transactionId, LocalDate invoiceClosingDate, Money amount, LocalDate date, boolean active) {

    public InvoicePayment {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(invoiceClosingDate, "invoiceClosingDate");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(date, "date");
    }

    public InvoicePayment deactivate() {
        return new InvoicePayment(transactionId, invoiceClosingDate, amount, date, false);
    }
}
