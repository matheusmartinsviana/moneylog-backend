package com.moneylog.core.finance.app.transactions.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;

public interface UpdateTransactionUseCase {

    String EDIT_REASON = "EDIT";

    TransactionDetails handle(UpdateTransactionCommand command);

    record UpdateTransactionCommand(UUID transactionId, long version, CreateTransactionCommand body) {
    }
}
