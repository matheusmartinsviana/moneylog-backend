package com.moneylog.core.goal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.moneylog.core.sk.money.Money;

public record GoalProgress(Money current, Money target, BigDecimal ratio) {

    public static final int RATIO_SCALE = 4;

    public static GoalProgress of(Money current, Money target) {
        BigDecimal ratio = BigDecimal.valueOf(current.cents()).divide(BigDecimal.valueOf(target.cents()), RATIO_SCALE, RoundingMode.HALF_UP);
        return new GoalProgress(current, target, ratio);
    }

    public Money remaining() {
        Money missing = target.subtract(current);
        return missing.isNegative() ? Money.zero(target.currency()) : missing;
    }

    public String ratioText() {
        return ratio.toPlainString();
    }
}
