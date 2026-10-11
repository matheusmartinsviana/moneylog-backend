package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.card.app.gateways.InvoiceLookupGateway;
import com.moneylog.core.card.app.gateways.InvoiceLookupGateway.InvoiceKey;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.CreditCardInvoice;
import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.core.card.exception.CardExceptions.InvoiceNotFoundException;
import com.moneylog.core.finance.app.cards.CardViews;
import com.moneylog.core.finance.domain.FinancialRecords;

final class InvoiceResolver {

    static final int MONTHS_BEHIND = 24;
    static final int MONTHS_AHEAD = 36;

    private InvoiceResolver() {
    }

    record Resolved(CreditCard card, LocalDate closingDate) {
    }

    static Resolved resolve(UUID userId, UUID invoiceId, InvoiceLookupGateway lookup, FinancialRecords records, LocalDate today) {
        Optional<InvoiceKey> row = lookup.findById(userId, invoiceId);
        if (row.isPresent()) {
            return records.cards().stream().filter(c -> c.id().equals(row.get().cardId())).findFirst()
                    .map(card -> new Resolved(card, row.get().closingDate()))
                    .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        }
        for (CreditCard card : records.cards()) {
            List<CreditCardInvoice> chain = CardViews.chainOf(card, records).invoices();
            YearMonth first = YearMonth.from(today).minusMonths(MONTHS_BEHIND);
            YearMonth last = YearMonth.from(today).plusMonths(MONTHS_AHEAD);
            if (!chain.isEmpty()) {
                YearMonth oldest = YearMonth.from(chain.getFirst().closingDate());
                YearMonth newest = YearMonth.from(chain.getLast().closingDate());
                first = oldest.isBefore(first) ? oldest : first;
                last = newest.isAfter(last) ? newest : last;
            }
            for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
                LocalDate closing = card.calendar().periodClosingIn(month).closingDate();
                if (InvoiceIds.of(card.id(), closing).equals(invoiceId)) {
                    return new Resolved(card, closing);
                }
            }
        }
        throw new InvoiceNotFoundException(invoiceId);
    }
}
