package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.sk.money.Money;

public interface CreateHistoricalInvoiceUseCase {

    /**
     * Registra o valor de uma fatura passada que o usuário só conhece pelo pagamento (ADR-014): um item {@code CARD −X / EQUITY +X} na
     * fatura que fechou em {@code closingDate}, sem categoria e sem consumo. Em seguida a fatura é paga pelo pagamento real
     * ({@code POST /v1/invoices/{id}/payments}). Fechamento que não é do calendário: {@code 404/422 INVOICE_CLOSING_DATE_NOT_FOUND};
     * fatura que ainda não fechou: {@code 422 HISTORICAL_INVOICE_NOT_CLOSED}; cartão arquivado: {@code 422 CARD_ARCHIVED}.
     */
    TransactionDetails handle(CreateHistoricalInvoiceCommand command);

    record CreateHistoricalInvoiceCommand(UUID userId, UUID cardId, LocalDate closingDate, Money amount, String description) {
    }
}
