package com.moneylog.core.finance.app.transactions.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;

public interface GetTransactionUseCase {

    TransactionDetails handle(GetTransactionQuery query);

    record GetTransactionQuery(UUID userId, UUID transactionId) {
    }
}
