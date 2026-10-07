package com.moneylog.core.finance.app.transactions.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;

public interface GetTransactionUseCase {

    /** @throws com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException inexistente ou de outro usuário */
    TransactionDetails handle(GetTransactionQuery query);

    record GetTransactionQuery(UUID userId, UUID transactionId) {
    }
}
