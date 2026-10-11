package com.moneylog.core.finance.app.accounts;

import java.time.Instant;
import java.util.Objects;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.sk.money.Money;

public record AccountDetails(Account account, Instant createdAt, Instant updatedAt, Money currentBalance,
                             boolean openingEditable, boolean archivable, Money reservedAmount, boolean uncovered) {

    public AccountDetails {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(currentBalance, "currentBalance");
        Objects.requireNonNull(reservedAmount, "reservedAmount");
    }

    public AccountDetails(Account account, Instant createdAt, Instant updatedAt, Money currentBalance, boolean openingEditable,
                          boolean archivable) {
        this(account, createdAt, updatedAt, currentBalance, openingEditable, archivable,
                Money.zero(currentBalance.currency()), false);
    }
}
