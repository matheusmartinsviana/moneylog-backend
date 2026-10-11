package com.moneylog.core.finance.app.accounts.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.accounts.AccountDetails;

public interface UnarchiveAccountUseCase {

    AccountDetails handle(UnarchiveAccountCommand command);

    record UnarchiveAccountCommand(UUID userId, UUID accountId) {
    }
}
