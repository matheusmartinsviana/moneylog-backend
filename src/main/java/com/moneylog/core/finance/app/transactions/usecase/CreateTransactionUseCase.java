package com.moneylog.core.finance.app.transactions.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.transaction.domain.TransactionOrigin;

public interface CreateTransactionUseCase {

    String IDEMPOTENCY_SCOPE = "POST /v1/transactions";

    TransactionDetails handle(CreateTransactionCommand command, UUID idempotencyKey);

    TransactionDetails handleImported(CreateTransactionCommand command, TransactionOrigin origin);

    default TransactionDetails handle(CreateTransactionCommand command) {
        return handle(command, null);
    }
}
