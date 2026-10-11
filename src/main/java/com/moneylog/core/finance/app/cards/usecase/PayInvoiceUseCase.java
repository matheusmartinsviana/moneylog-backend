package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.cards.InvoiceDetails;
import com.moneylog.core.sk.money.Money;

public interface PayInvoiceUseCase {

    String IDEMPOTENCY_SCOPE = "POST /v1/invoices/{id}/payments";

    InvoiceDetails handle(PayInvoiceCommand command, UUID idempotencyKey);

    default InvoiceDetails handle(PayInvoiceCommand command) {
        return handle(command, null);
    }

    record PayInvoiceCommand(UUID userId, UUID invoiceId, UUID accountId, Money amount, LocalDate date) {
    }
}
