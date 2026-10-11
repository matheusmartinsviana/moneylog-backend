package com.moneylog.core.goal.domain;

import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.LiquidityClass;
import com.moneylog.core.goal.exception.GoalExceptions.GoalReservationAccountNotEligibleException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalReservationAmountZeroException;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public record ReserveAllocation(UUID accountId, Money amount) {

    public static ReserveAllocation of(Account account, Money amount) {
        if (amount == null || amount.isZero()) {
            throw new GoalReservationAmountZeroException();
        }
        if (!isEligible(account.liquidity(), account.currency())) {
            throw new GoalReservationAccountNotEligibleException("só contas de caixa ou de investimento em BRL.");
        }
        if (!CurrencyCode.BRL.equals(amount.currency())) {
            throw new GoalReservationAccountNotEligibleException("a moeda da reserva precisa ser BRL.");
        }
        AmountLimit.ensureWithinLimit(amount, "amount");
        if (amount.isPositive()) {
            account.ensureCanReceiveTransactions();
        }
        return new ReserveAllocation(account.id(), amount);
    }

    public static boolean isEligible(LiquidityClass liquidity, CurrencyCode currency) {
        return (liquidity == LiquidityClass.CASH || liquidity == LiquidityClass.INVESTMENT) && CurrencyCode.BRL.equals(currency);
    }
}
