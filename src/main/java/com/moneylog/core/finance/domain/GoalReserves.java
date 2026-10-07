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

/**
 * Lente de reservas das metas (D1 §2.9, S-147, S-148): o <b>único</b> cálculo de "reservado", usado pelo dashboard, pelo saldo da conta
 * e pela meta. Reserva lógica: não é lançamento, não muda saldo nem movimenta nada.
 *
 * <ul>
 *   <li>{@code byAccount} = Σ reservas líquidas (reserva − liberação) das metas <b>não arquivadas</b> com data ≤ {@code asOf}, por conta;</li>
 *   <li>{@code inCash} = Σ {@code byAccount} das contas {@code CASH} (as de investimento já estão fora do disponível, S-21);</li>
 *   <li>{@code uncovered} = contas cujo reservado passa do saldo (S-148): só sinaliza, nunca bloqueia.</li>
 * </ul>
 */
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
