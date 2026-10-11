package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.sk.money.Money;

public interface CreateOngoingInstallmentsUseCase {

    TransactionDetails handle(CreateOngoingInstallmentsCommand command);

    record CreateOngoingInstallmentsCommand(UUID userId, UUID cardId, String description, Money installmentAmount,
                                            int totalInstallments, int remainingInstallments, LocalDate firstInvoiceDate,
                                            UUID categoryId) {
    }
}
