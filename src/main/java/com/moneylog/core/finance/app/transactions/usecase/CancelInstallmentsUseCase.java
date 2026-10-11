package com.moneylog.core.finance.app.transactions.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;

public interface CancelInstallmentsUseCase {

    String CANCEL_REASON = "CANCEL";

    String REVISION_CHANGE = "CANCEL_INSTALLMENTS";

    TransactionDetails handle(CancelInstallmentsCommand command);

    record CancelInstallmentsCommand(UUID userId, UUID transactionId, String reason) {
    }
}
