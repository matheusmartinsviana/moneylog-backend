package com.moneylog.infra.card.controller;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.finance.app.cards.InvoiceView;
import com.moneylog.core.sk.money.Money;

public record InvoiceResponse(UUID id, UUID cardId, String referenceMonth, LocalDate periodStart, LocalDate closingDate,
                              LocalDate dueDate, InvoiceStatus status, Money total, Money carryIn, Money paid, Money outstanding,
                              Money creditCarry, boolean hasLateEntries) {

    public static InvoiceResponse of(InvoiceView view) {
        return new InvoiceResponse(view.id(), view.cardId(), view.referenceMonth().toString(), view.periodStart(),
                view.closingDate(), view.dueDate(), view.status(), view.total(), view.carryIn(), view.paid(), view.outstanding(),
                view.creditCarry(), view.hasLateEntries());
    }
}
