package com.moneylog.core.finance.app.accounts.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.accounts.AccountDetails;

public interface UnarchiveAccountUseCase {

    /** Volta a conta para {@code ACTIVE}; desarquivar uma conta ativa é {@code ACCOUNT_NOT_ARCHIVED}. */
    AccountDetails handle(UnarchiveAccountCommand command);

    record UnarchiveAccountCommand(UUID userId, UUID accountId) {
    }
}
