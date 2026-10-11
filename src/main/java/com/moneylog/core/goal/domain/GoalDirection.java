package com.moneylog.core.goal.domain;

import com.moneylog.core.sk.money.Money;

public enum GoalDirection {
    DECREASE,
    INCREASE;

    public static GoalDirection derive(Money target, Money baseline) {
        int cmp = target.compareTo(baseline);
        return cmp == 0 ? null : cmp < 0 ? DECREASE : INCREASE;
    }
}
