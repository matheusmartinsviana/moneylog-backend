package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.InvoiceDetails;
import com.moneylog.core.sk.money.Money;

public interface ChangeInvoiceDocumentAmountUseCase {

    InvoiceDetails handle(ChangeInvoiceDocumentAmountCommand command);

    record ChangeInvoiceDocumentAmountCommand(UUID userId, UUID invoiceId, Money documentAmount, Long version) {
    }
}
