package com.moneylog.core.goal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.moneylog.core.sk.money.Money;

/**
 * Valor atual, alvo e razão {@code current / target} com 4 casas (0,4250 = 42,5%, PR 00 §17), calculados no backend.
 * A razão não é limitada a 1: meta passada do alvo mostra mais de 100%.
 */
public record GoalProgress(Money current, Money target, BigDecimal ratio) {

    public static final int RATIO_SCALE = 4;

    public static GoalProgress of(Money current, Money target) {
        BigDecimal ratio = BigDecimal.valueOf(current.cents()).divide(BigDecimal.valueOf(target.cents()), RATIO_SCALE, RoundingMode.HALF_UP);
        return new GoalProgress(current, target, ratio);
    }

    /** {@code target − current}, nunca negativo: o que falta. */
    public Money remaining() {
        Money missing = target.subtract(current);
        return missing.isNegative() ? Money.zero(target.currency()) : missing;
    }

    /** Razão em texto, sempre com {@value #RATIO_SCALE} casas. */
    public String ratioText() {
        return ratio.toPlainString();
    }
}
