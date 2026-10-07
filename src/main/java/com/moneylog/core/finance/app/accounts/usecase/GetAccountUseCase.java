package com.moneylog.core.finance.app.accounts.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.accounts.AccountDetails;

public interface GetAccountUseCase {

    /** @throws com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException inexistente ou de outro usuário */
    AccountDetails handle(GetAccountQuery query);

    record GetAccountQuery(UUID userId, UUID accountId) {
    }
}
