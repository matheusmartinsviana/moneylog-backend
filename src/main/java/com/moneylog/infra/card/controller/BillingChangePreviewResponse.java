package com.moneylog.infra.card.controller;

import java.time.LocalDate;

import com.moneylog.core.finance.app.cards.BillingChangePreviewView;

/** Saída da prévia de mudança de dias (contrato: {@code BillingChangePreview}). */
public record BillingChangePreviewResponse(LocalDate effectiveFromClosingDate, FirstNewInvoice firstNewInvoice, int movedInstallments,
                                           int movedCredits, String notice) {

    /** A mudança vale da fatura seguinte à corrente (S-42). */
    public static final String FROM_NEXT_INVOICE = "FROM_NEXT_INVOICE";

    public record FirstNewInvoice(LocalDate closingDate, LocalDate dueDate) {
    }

    public static BillingChangePreviewResponse of(BillingChangePreviewView view) {
        return new BillingChangePreviewResponse(view.effectiveFromClosingDate(),
                new FirstNewInvoice(view.firstNewClosingDate(), view.firstNewDueDate()), view.movedInstallments(), view.movedCredits(),
                FROM_NEXT_INVOICE);
    }
}
