package com.moneylog.core.goal.domain;

import java.math.BigDecimal;
import java.util.List;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public record GoalConsistency(List<Month> history, int streak, BigDecimal rate) {

    public GoalConsistency {
        history = List.copyOf(history);
    }

    public static GoalConsistency none() {
        return new GoalConsistency(List.of(), 0, null);
    }

    public record Month(Competence month, Money expected, Money actual, Boolean met, boolean closed) {
    }
}
