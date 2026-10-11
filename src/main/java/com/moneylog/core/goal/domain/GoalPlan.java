package com.moneylog.core.goal.domain;

import com.moneylog.core.sk.money.Money;

public record GoalPlan(Money requiredPace, int monthsLeft, Money nextMilestone, boolean onTrack, Money gap) {
}
