package com.moneylog.core.finance.app.accounts.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.accounts.AccountDetails;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.sk.money.Money;

public interface UpdateAccountUseCase {

    AccountDetails handle(UpdateAccountCommand command);

    record UpdateAccountCommand(UUID userId, UUID accountId, String name, String institution, AccountType type,
                                Money openingBalance, LocalDate openingDate, long version) {
    }
}
