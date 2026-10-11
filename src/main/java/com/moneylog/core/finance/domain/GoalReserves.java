package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.LiquidityClass;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public record GoalReserves(LocalDate asOf, Map<UUID, Money> byAccount, Money inCash, Set<UUID> uncovered) {

    public static GoalReserves at(FinancialRecords records, CashPosition position) {
        LocalDate asOf = position.asOf();
        Money zero = Money.zero(CurrencyCode.BRL);
        Map<UUID, Money> byAccount = new LinkedHashMap<>();
        for (Goal goal : records.goals()) {
            for (UUID accountId : goal.reservedAccounts(asOf)) {
                byAccount.merge(accountId, goal.reservedOn(accountId, asOf), Money::add);
            }
        }
        Money inCash = zero;
        Set<UUID> uncovered = new java.util.LinkedHashSet<>();
        for (Account account : records.accounts()) {
            Money reserved = byAccount.getOrDefault(account.id(), zero);
            if (account.liquidity() == LiquidityClass.CASH) {
                inCash = inCash.add(reserved);
            }
            if (reserved.isPositive() && reserved.isGreaterThan(position.balanceOf(account.id()))) {
                uncovered.add(account.id());
            }
        }
        return new GoalReserves(asOf, Map.copyOf(byAccount), inCash, Set.copyOf(uncovered));
    }

    public Money reservedOn(UUID accountId) {
        return byAccount.getOrDefault(accountId, Money.zero(CurrencyCode.BRL));
    }

    public boolean isUncovered(UUID accountId) {
        return uncovered.contains(accountId);
    }
}
