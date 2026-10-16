package com.moneylog.core.recurrence.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Collection;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public final class SubscriptionCost {

    public record Monthly(BigInteger numerator, BigInteger denominator) {

        public static final Monthly ZERO = new Monthly(BigInteger.ZERO, BigInteger.ONE);

        public Monthly plus(Monthly other) {
            return new Monthly(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                    denominator.multiply(other.denominator));
        }

        public Money money() {
            long cents = new BigDecimal(numerator).divide(new BigDecimal(denominator), 0, RoundingMode.HALF_EVEN).longValueExact();
            return Money.ofCents(cents, CurrencyCode.BRL);
        }
    }

    private SubscriptionCost() {
    }

    public static Monthly full(RecurringTransaction recurrence) {
        return of(recurrence, recurrence.amount());
    }

    public static Monthly mine(RecurringTransaction recurrence) {
        if (recurrence.traits().scope() == RecurrenceScope.COMPANY) {
            return Monthly.ZERO;
        }
        Money mineCharge = recurrence.purchaseShares() == null ? recurrence.amount()
                : recurrence.purchaseShares().split(recurrence.amount()).mine();
        return of(recurrence, mineCharge);
    }

    public static Monthly sum(Collection<Monthly> parts) {
        Monthly total = Monthly.ZERO;
        for (Monthly part : parts) {
            total = total.plus(part);
        }
        return total;
    }

    private static Monthly of(RecurringTransaction recurrence, Money charge) {
        BigInteger[] factor = recurrence.schedule().monthlyFactor();
        return new Monthly(BigInteger.valueOf(charge.cents()).multiply(factor[0]), factor[1]);
    }
}
