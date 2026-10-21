package com.moneylog.core.quickentry.app.usecase;

import java.util.UUID;

import com.moneylog.core.account.app.gateways.AccountGateway;

final class AccountNames {

    private AccountNames() {
    }

    static String of(AccountGateway accounts, UUID userId, UUID accountId) {
        return accounts.findById(userId, accountId).map(found -> found.value().name()).orElse("");
    }
}
