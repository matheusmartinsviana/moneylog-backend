package com.moneylog.infra.account.controller;

import java.time.LocalDate;

import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.sk.money.Money;

import com.moneylog.infra.web.validation.DateInWindow;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AccountRequests {

    private AccountRequests() {
    }

    public record AccountInput(@NotBlank @Size(max = 100) @NoControlChars String name, @NotNull AccountType type,
                               @Size(max = 100) @NoControlChars String institution, Money openingBalance,
                               @DateInWindow LocalDate openingDate) {
    }

    public record AccountUpdate(@NotBlank @Size(max = 100) @NoControlChars String name, @Size(max = 100) @NoControlChars String institution,
                                AccountType type, Money openingBalance, @DateInWindow LocalDate openingDate, @NotNull Long version) {
    }
}
