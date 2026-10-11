package com.moneylog.core.account.util.factory;

import static com.moneylog.core.util.Ids.USER_ID;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.sk.money.Money;

public final class AccountTestFactory {

    public static final UUID NUBANK_ID = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    public static final UUID XP_ID = UUID.fromString("00000000-0000-0000-0000-00000000a002");
    public static final UUID XP_INVEST_ID = UUID.fromString("00000000-0000-0000-0000-00000000a003");
    public static final LocalDate OPENING_DATE = LocalDate.of(2026, 10, 1);
    public static final Money NUBANK_OPENING_BALANCE = Money.of("20000.00");

    private AccountTestFactory() {
    }

    public static Account umaContaCorrente() {
        return Account.open(NUBANK_ID, USER_ID, "Nubank", AccountType.CHECKING, "Nubank", NUBANK_OPENING_BALANCE, OPENING_DATE);
    }

    public static Account umaContaSemSaldo() {
        return Account.open(XP_ID, USER_ID, "XP", AccountType.CHECKING, "XP", Money.of("0.00"), OPENING_DATE);
    }

    public static Account umaContaDeInvestimento() {
        return Account.open(XP_INVEST_ID, USER_ID, "XP Invest", AccountType.INVESTMENT, "XP", Money.of("0.00"), OPENING_DATE);
    }

    public static Account umaContaDoTipo(AccountType type) {
        return Account.open(NUBANK_ID, USER_ID, "Conta " + type, type, null, Money.of("0.00"), OPENING_DATE);
    }

    public static Account umaContaArquivada() {
        return Account.restore(XP_ID, USER_ID, "XP", AccountType.CHECKING, "XP", Money.of("0.00"), OPENING_DATE,
                AccountStatus.ARCHIVED, 3L);
    }

    public static Account umaContaNoStatus(AccountStatus status) {
        return Account.restore(XP_ID, USER_ID, "XP", AccountType.CHECKING, "XP", Money.of("0.00"), OPENING_DATE, status, 1L);
    }
}
