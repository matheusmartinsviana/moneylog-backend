package com.moneylog.core.finance.app.cards;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import com.moneylog.core.card.domain.CreditCardInvoice;
import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.sk.money.Money;

/**
 * Fatura como a API a mostra (D2 §4.3 {@code InvoiceSummary}): datas, status derivado e valores calculados pela cadeia
 * do cartão. O {@code id} é o determinístico de {@link InvoiceIds} (ADR-006): existe com ou sem linha gravada.
 */
public record InvoiceView(UUID id, UUID cardId, YearMonth referenceMonth, LocalDate periodStart, LocalDate closingDate,
                          LocalDate dueDate, InvoiceStatus status, Money total, Money carryIn, Money paid, Money outstanding,
                          Money creditCarry, boolean hasLateEntries) {

    public static InvoiceView of(UUID cardId, CreditCardInvoice invoice, LocalDate today) {
        return new InvoiceView(InvoiceIds.of(cardId, invoice.closingDate()), cardId, invoice.referenceMonth(),
                invoice.period().periodStart(), invoice.closingDate(), invoice.dueDate(), invoice.statusAt(today),
                invoice.total(), invoice.carryIn(), invoice.paid(), invoice.outstanding(), invoice.creditCarry(),
                invoice.hasLateEntries());
    }
}
