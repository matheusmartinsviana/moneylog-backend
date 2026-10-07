package com.moneylog.infra.card.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.app.cards.FutureInstallmentsView;
import com.moneylog.core.sk.money.Money;

/** Saída de {@code GET /v1/cards/{id}/future-installments} (contrato: {@code FutureInstallments}). */
public record FutureInstallmentsResponse(Money total, List<ByInvoiceResponse> byInvoice) {

    public record ByInvoiceResponse(UUID invoiceId, String referenceMonth, LocalDate dueDate, Money amount,
                                    List<InvoiceDetailResponse.ItemResponse> items) {
    }

    public static FutureInstallmentsResponse of(FutureInstallmentsView view) {
        return new FutureInstallmentsResponse(view.total(), view.byInvoice().stream()
                .map(b -> new ByInvoiceResponse(b.invoiceId(), b.referenceMonth().toString(), b.dueDate(), b.amount(),
                        b.items().stream().map(InvoiceDetailResponse.ItemResponse::of).toList()))
                .toList());
    }
}
