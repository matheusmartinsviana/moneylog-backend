package com.moneylog.core.recurrence.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.FlowDirection;
import com.moneylog.core.sk.money.Money;

public record MonthlyEstimate(Money fixedCosts, Money recurringIncome, boolean estimate, boolean gross) {

    public MonthlyEstimate(Money fixedCosts, Money recurringIncome, boolean estimate) {
        this(fixedCosts, recurringIncome, estimate, false);
    }

    public static MonthlyEstimate of(Collection<RecurringTransaction> recurrences, LocalDate today) {
        return of(recurrences, today, false);
    }

    public static MonthlyEstimate of(Collection<RecurringTransaction> recurrences, LocalDate today, boolean gross) {
        return new MonthlyEstimate(sum(recurrences, FlowDirection.OUT, today, gross), sum(recurrences, FlowDirection.IN, today, gross), true, gross);
    }

    private static Money installmentOf(RecurringTransaction r, boolean gross) {
        if (gross || r.purchaseShares() == null) {
            return r.amount();
        }
        return r.purchaseShares().split(r.amount()).mine();
    }

    private static Money sum(Collection<RecurringTransaction> recurrences, FlowDirection direction, LocalDate today, boolean gross) {
        BigInteger numerator = BigInteger.ZERO;
        BigInteger denominator = BigInteger.ONE;
        for (RecurringTransaction r : recurrences) {
            if (!r.isActive() || r.isEnded(today) || r.direction() != direction) {
                continue;
            }
            BigInteger[] factor = r.schedule().monthlyFactor();
            BigInteger n = BigInteger.valueOf(installmentOf(r, gross).cents()).multiply(factor[0]);
            numerator = numerator.multiply(factor[1]).add(n.multiply(denominator));
            denominator = denominator.multiply(factor[1]);
        }
        long cents = new BigDecimal(numerator).divide(new BigDecimal(denominator), 0, RoundingMode.HALF_EVEN).longValueExact();
        return Money.ofCents(cents, CurrencyCode.BRL);
    }
}
