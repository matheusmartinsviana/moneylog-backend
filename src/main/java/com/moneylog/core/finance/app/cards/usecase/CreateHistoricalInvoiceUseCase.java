package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.sk.money.Money;

public interface CreateHistoricalInvoiceUseCase {

    TransactionDetails handle(CreateHistoricalInvoiceCommand command);

    record CreateHistoricalInvoiceCommand(UUID userId, UUID cardId, LocalDate closingDate, Money amount, String description) {
    }
}
