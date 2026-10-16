package com.moneylog.core.finance.app.cards;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.card.app.gateways.InvoiceDocumentGateway;
import com.moneylog.core.card.domain.InvoiceDocument;
import com.moneylog.core.card.domain.InvoiceItemKind;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.PersonAmount;

public record InvoiceDetails(InvoiceView invoice, List<Item> items, List<Payment> payments, Money myShare, Money thirdPartyShare,
                             List<PersonAmount> shareByPerson, Money documentAmount, Money documentDivergence, long documentVersion) {

    public InvoiceDetails {
        Objects.requireNonNull(invoice, "invoice");
        items = List.copyOf(items);
        payments = List.copyOf(payments);
        Objects.requireNonNull(myShare, "myShare");
        Objects.requireNonNull(thirdPartyShare, "thirdPartyShare");
        shareByPerson = List.copyOf(shareByPerson);
    }

    public InvoiceDetails withDocument(InvoiceDocumentGateway.Stored stored) {
        Money divergence = InvoiceDocument.divergence(invoice.total(), stored.amount()).map(InvoiceDocument.Divergence::difference).orElse(null);
        return new InvoiceDetails(invoice, items, payments, myShare, thirdPartyShare, shareByPerson, stored.amount(), divergence, stored.version());
    }

    public record Item(InvoiceItemKind kind, UUID transactionId, String description, Integer number, Integer totalInstallments,
                       Money amount, LocalDate date, boolean lateEntry) {
    }

    public record Payment(UUID transactionId, UUID accountId, Money amount, LocalDate date) {
    }
}
