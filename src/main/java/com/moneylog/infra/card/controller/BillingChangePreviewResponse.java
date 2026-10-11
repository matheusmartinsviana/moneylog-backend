package com.moneylog.infra.card.controller;

import java.time.LocalDate;

import com.moneylog.core.finance.app.cards.BillingChangePreviewView;

public record BillingChangePreviewResponse(LocalDate effectiveFromClosingDate, FirstNewInvoice firstNewInvoice, int movedInstallments,
                                           int movedCredits, String notice) {

    public static final String FROM_NEXT_INVOICE = "FROM_NEXT_INVOICE";

    public record FirstNewInvoice(LocalDate closingDate, LocalDate dueDate) {
    }

    public static BillingChangePreviewResponse of(BillingChangePreviewView view) {
        return new BillingChangePreviewResponse(view.effectiveFromClosingDate(),
                new FirstNewInvoice(view.firstNewClosingDate(), view.firstNewDueDate()), view.movedInstallments(), view.movedCredits(),
                FROM_NEXT_INVOICE);
    }
}
