package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.InvoiceDetails;

public interface GetInvoiceUseCase {

    /**
     * A fatura com itens e pagamentos ativos. O id é o determinístico (ADR-006): a fatura sem linha (a corrente vazia)
     * responde com total 0,00.
     *
     * @throws com.moneylog.core.card.exception.CardExceptions.InvoiceNotFoundException id de outro usuário, de outro
     *                                                                                  cartão ou fora da janela
     */
    InvoiceDetails handle(GetInvoiceQuery query);

    record GetInvoiceQuery(UUID userId, UUID invoiceId) {
    }
}
