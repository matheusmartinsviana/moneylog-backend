package com.moneylog.core.transaction.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.sk.money.Money;

public record VoidContext(String reason, Instant now, LocalDate today, CreditCard card, InvoiceChain chain, Debt debt,
                          Collection<Account> accounts, Money activeRefunds, Collection<Debt> shareDebts) {

    public VoidContext {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(today, "today");
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        shareDebts = shareDebts == null ? List.of() : List.copyOf(shareDebts);
    }

    public VoidContext(String reason, Instant now, LocalDate today, CreditCard card, InvoiceChain chain, Debt debt,
                       Collection<Account> accounts, Money activeRefunds) {
        this(reason, now, today, card, chain, debt, accounts, activeRefunds, List.of());
    }

    public VoidContext(String reason, Instant now, LocalDate today, CreditCard card, InvoiceChain chain, Debt debt) {
        this(reason, now, today, card, chain, debt, List.of(), null, List.of());
    }

    public static VoidContext of(String reason, Instant now, LocalDate today) {
        return new VoidContext(reason, now, today, null, null, null, List.of(), null, List.of());
    }

    public VoidContext withCard(CreditCard newCard, InvoiceChain newChain) {
        return new VoidContext(reason, now, today, newCard, newChain, debt, accounts, activeRefunds, shareDebts);
    }

    public VoidContext withDebt(Debt newDebt) {
        return new VoidContext(reason, now, today, card, chain, newDebt, accounts, activeRefunds, shareDebts);
    }

    public VoidContext withAccounts(Collection<Account> touched) {
        return new VoidContext(reason, now, today, card, chain, debt, touched, activeRefunds, shareDebts);
    }

    public VoidContext withActiveRefunds(Money refunds) {
        return new VoidContext(reason, now, today, card, chain, debt, accounts, refunds, shareDebts);
    }

    public VoidContext withShareDebts(Collection<Debt> debts) {
        return new VoidContext(reason, now, today, card, chain, debt, accounts, activeRefunds, debts);
    }
}
