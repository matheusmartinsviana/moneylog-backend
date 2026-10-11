package com.moneylog.core.recurrence.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.FlowDirection;
import com.moneylog.core.sk.money.Money;

public record MonthlyEstimate(Money fixedCosts, Money recurringIncome, boolean estimate) {

    public static MonthlyEstimate of(Collection<RecurringTransaction> recurrences, LocalDate today) {
        return new MonthlyEstimate(sum(recurrences, FlowDirection.OUT, today), sum(recurrences, FlowDirection.IN, today), true);
    }

    private static Money sum(Collection<RecurringTransaction> recurrences, FlowDirection direction, LocalDate today) {
        BigInteger numerator = BigInteger.ZERO;
        BigInteger denominator = BigInteger.ONE;
        for (RecurringTransaction r : recurrences) {
            if (!r.isActive() || r.isEnded(today) || r.direction() != direction) {
                continue;
            }
            BigInteger[] factor = r.schedule().monthlyFactor();
            BigInteger n = BigInteger.valueOf(r.amount().cents()).multiply(factor[0]);
            numerator = numerator.multiply(factor[1]).add(n.multiply(denominator));
            denominator = denominator.multiply(factor[1]);
        }
        long cents = new BigDecimal(numerator).divide(new BigDecimal(denominator), 0, RoundingMode.HALF_EVEN).longValueExact();
        return Money.ofCents(cents, CurrencyCode.BRL);
    }
}
