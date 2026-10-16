package com.moneylog.core.income.domain;

import java.util.Objects;

import com.moneylog.core.sk.money.Money;

public record TaxReduction(Money fullReductionUpTo, Money fullReductionAmount, Money phaseOutUpTo, Money constant,
                           long coefficientMicros) {

    public static final long MICROS = 1_000_000L;

    public TaxReduction {
        Objects.requireNonNull(fullReductionUpTo, "fullReductionUpTo");
        Objects.requireNonNull(fullReductionAmount, "fullReductionAmount");
        Objects.requireNonNull(phaseOutUpTo, "phaseOutUpTo");
        Objects.requireNonNull(constant, "constant");
        if (coefficientMicros < 0 || phaseOutUpTo.isLessThan(fullReductionUpTo)) {
            throw new IllegalArgumentException("redutor inválido.");
        }
    }

    public Money reductionFor(Money taxableIncome) {
        if (!taxableIncome.isGreaterThan(fullReductionUpTo)) {
            return fullReductionAmount;
        }
        if (taxableIncome.isGreaterThan(phaseOutUpTo)) {
            return Money.zero(taxableIncome.currency());
        }
        long scaled = Math.subtractExact(Math.multiplyExact(constant.cents(), MICROS),
                Math.multiplyExact(taxableIncome.cents(), coefficientMicros));
        long cents = TaxMath.divideHalfUp(Math.max(0L, scaled), MICROS);
        return Money.ofCents(cents, taxableIncome.currency());
    }

    public String coefficientText() {
        return (coefficientMicros / MICROS) + "." + String.format("%06d", coefficientMicros % MICROS);
    }
}
