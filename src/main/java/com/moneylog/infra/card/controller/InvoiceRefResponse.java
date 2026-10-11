package com.moneylog.infra.card.controller;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.finance.app.cards.InvoiceView;
import com.moneylog.core.finance.app.cards.PurchasePreviewView.InvoiceTarget;

public record InvoiceRefResponse(UUID id, String referenceMonth, LocalDate closingDate, LocalDate dueDate, InvoiceStatus status) {

    public static InvoiceRefResponse of(InvoiceView view) {
        return new InvoiceRefResponse(view.id(), view.referenceMonth().toString(), view.closingDate(), view.dueDate(), view.status());
    }

    public static InvoiceRefResponse of(InvoiceTarget target) {
        return new InvoiceRefResponse(target.id(), target.referenceMonth().toString(), target.closingDate(), target.dueDate(),
                target.status());
    }
}
