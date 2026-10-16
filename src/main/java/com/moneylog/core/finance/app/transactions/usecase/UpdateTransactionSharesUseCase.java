package com.moneylog.core.finance.app.transactions.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.transaction.domain.ShareLine;
import com.moneylog.core.transaction.domain.SplitMode;

public interface UpdateTransactionSharesUseCase {

    String REVISION_CHANGE = "UPDATE_SHARES";

    TransactionDetails handle(UpdateTransactionSharesCommand command);

    record UpdateTransactionSharesCommand(UUID userId, UUID transactionId, long version, SplitMode splitMode, List<ShareLine> shares) {

        public UpdateTransactionSharesCommand {
            shares = shares == null ? List.of() : List.copyOf(shares);
        }
    }
}
