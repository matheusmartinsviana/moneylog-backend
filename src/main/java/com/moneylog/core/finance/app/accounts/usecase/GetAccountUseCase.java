package com.moneylog.core.finance.app.accounts.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.accounts.AccountDetails;

public interface GetAccountUseCase {

    AccountDetails handle(GetAccountQuery query);

    record GetAccountQuery(UUID userId, UUID accountId) {
    }
}
