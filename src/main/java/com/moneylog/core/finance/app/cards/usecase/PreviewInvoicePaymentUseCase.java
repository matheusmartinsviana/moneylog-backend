package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.PaymentPreviewView;
import com.moneylog.core.sk.money.Money;

public interface PreviewInvoicePaymentUseCase {

    /**
     * O que o pagamento de {@code amount} deixaria na fatura (S-136), pelo mesmo caminho do pagamento real e sem gravar
     * nada. Fatura {@code FUTURE}/{@code PAID}: {@code INVOICE_NOT_PAYABLE}.
     */
    PaymentPreviewView handle(PreviewInvoicePaymentQuery query);

    record PreviewInvoicePaymentQuery(UUID userId, UUID invoiceId, Money amount) {
    }
}
