package com.moneylog.core.goal.domain;

import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.LiquidityClass;
import com.moneylog.core.goal.exception.GoalExceptions.GoalReservationAccountNotEligibleException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalReservationAmountZeroException;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

/**
 * Onde e quanto reservar: a conta já validada contra a regra da reserva (S-147). Só conta {@code CASH} ou
 * {@code INVESTMENT} em BRL; conta arquivada <b>não recebe reserva nova</b>, mas aceita liberação (é assim que o dinheiro
 * volta a ser disponível).
 */
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

    /** Elegível: liquidez {@code CASH} ou {@code INVESTMENT} e moeda BRL. */
    public static boolean isEligible(LiquidityClass liquidity, CurrencyCode currency) {
        return (liquidity == LiquidityClass.CASH || liquidity == LiquidityClass.INVESTMENT) && CurrencyCode.BRL.equals(currency);
    }
}
