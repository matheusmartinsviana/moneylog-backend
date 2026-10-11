package com.moneylog.core.goal.domain;

import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public record GoalSnapshot(UUID goalId, Competence month, Money value, Instant capturedAt) {
}
