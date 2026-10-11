package com.moneylog.core.finance.app.accounts.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.accounts.AccountDetails;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.sk.money.Money;

public interface CreateAccountUseCase {

    AccountDetails handle(CreateAccountCommand command);

    record CreateAccountCommand(UUID userId, String name, AccountType type, String institution, Money openingBalance,
                                LocalDate openingDate) {
    }
}
