package com.moneylog.core.finance.app.accounts.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.accounts.AccountDetails;

public interface ArchiveAccountUseCase {

    AccountDetails handle(ArchiveAccountCommand command);

    record ArchiveAccountCommand(UUID userId, UUID accountId) {
    }
}
