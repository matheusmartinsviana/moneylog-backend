package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.LiquidityClass;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

/**
 * Lente de caixa (D1 §1): saldo de cada conta = Σ postings ativos do livro {@code ACCOUNT} com data ≤ a data
 * consultada. "Em contas" soma as contas {@code CASH}; "investimentos", as {@code INVESTMENT}. Movimentação com data
 * futura ainda não entra.
 */
public record CashPosition(LocalDate asOf, Map<UUID, Money> byAccount, Money inAccounts, Money investments) {

    public static CashPosition at(FinancialRecords records, Ledger ledger, LocalDate asOf) {
        Map<UUID, Money> byAccount = new LinkedHashMap<>();
        Money cash = Money.zero(CurrencyCode.BRL);
        Money investments = Money.zero(CurrencyCode.BRL);
        for (Account account : records.accounts()) {
            Money balance = ledger.balance(account.book(), asOf);
            byAccount.put(account.id(), balance);
            if (account.liquidity() == LiquidityClass.CASH) {
                cash = cash.add(balance);
            } else {
                investments = investments.add(balance);
            }
        }
        return new CashPosition(asOf, Map.copyOf(byAccount), cash, investments);
    }

    public Money balanceOf(UUID accountId) {
        return byAccount.getOrDefault(accountId, Money.zero(CurrencyCode.BRL));
    }
}
