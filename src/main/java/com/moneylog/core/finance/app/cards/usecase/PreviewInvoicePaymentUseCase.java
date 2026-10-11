package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.PaymentPreviewView;
import com.moneylog.core.sk.money.Money;

public interface PreviewInvoicePaymentUseCase {

    PaymentPreviewView handle(PreviewInvoicePaymentQuery query);

    record PreviewInvoicePaymentQuery(UUID userId, UUID invoiceId, Money amount) {
    }
}
