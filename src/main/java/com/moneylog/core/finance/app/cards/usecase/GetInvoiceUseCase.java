package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.InvoiceDetails;

public interface GetInvoiceUseCase {

    InvoiceDetails handle(GetInvoiceQuery query);

    record GetInvoiceQuery(UUID userId, UUID invoiceId) {
    }
}
