package com.moneylog.core.finance.app.cards;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.card.domain.InvoiceItemKind;
import com.moneylog.core.sk.money.Money;

public record InvoiceDetails(InvoiceView invoice, List<Item> items, List<Payment> payments) {

    public InvoiceDetails {
        Objects.requireNonNull(invoice, "invoice");
        items = List.copyOf(items);
        payments = List.copyOf(payments);
    }

    public record Item(InvoiceItemKind kind, UUID transactionId, String description, Integer number, Integer totalInstallments,
                       Money amount, LocalDate date, boolean lateEntry) {
    }

    public record Payment(UUID transactionId, UUID accountId, Money amount, LocalDate date) {
    }
}
