package com.moneylog.core.finance.app.transactions.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;

public interface VoidTransactionUseCase {

    String VOID_REASON = "VOID";

    TransactionDetails handle(VoidTransactionCommand command);

    record VoidTransactionCommand(UUID userId, UUID transactionId, String reason) {
    }
}
