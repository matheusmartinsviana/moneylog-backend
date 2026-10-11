package com.moneylog.core.insights.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

/** Conversões exatas entre {@link Money} (centavos), {@link BigDecimal} e o texto decimal dos {@code params} (o front só formata). */
final class Fmt {

    private Fmt() {
    }

    static BigDecimal dec(Money money) {
        return BigDecimal.valueOf(money.cents());
    }

    static Money money(BigDecimal cents) {
        return Money.ofCents(cents.setScale(0, RoundingMode.HALF_UP).longValueExact(), CurrencyCode.BRL);
    }

    static Money zero() {
        return Money.zero(CurrencyCode.BRL);
    }

    /** {@code numerator / denominator} com {@value Stats#SCALE} casas; o denominador precisa ser diferente de zero. */
    static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, Stats.SCALE, RoundingMode.HALF_UP);
    }

    static BigDecimal ratio(Money numerator, Money denominator) {
        return ratio(dec(numerator), dec(denominator));
    }

    /** Razão em pontos percentuais com 2 casas ({@code 0,2 -> "20.00"}). */
    static String percent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String number(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }
}
