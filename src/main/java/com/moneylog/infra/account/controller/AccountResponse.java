package com.moneylog.infra.account.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.accounts.AccountDetails;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.account.domain.LiquidityClass;
import com.moneylog.core.sk.money.Money;

public record AccountResponse(UUID id, String name, AccountType type, LiquidityClass liquidity, String institution,
                              Money openingBalance, LocalDate openingDate, Money currentBalance, AccountStatus status,
                              boolean openingEditable, boolean archivable, Money reservedAmount, boolean uncovered, long version,
                              Instant createdAt, Instant updatedAt) {

    public static AccountResponse of(AccountDetails details) {
        Account account = details.account();
        return new AccountResponse(account.id(), account.name(), account.type(), account.liquidity(), account.institution(),
                account.openingBalance(), account.openingDate(), details.currentBalance(), account.status(),
                details.openingEditable(), details.archivable(), details.reservedAmount(), details.uncovered(), account.version(),
                details.createdAt(), details.updatedAt());
    }
}
