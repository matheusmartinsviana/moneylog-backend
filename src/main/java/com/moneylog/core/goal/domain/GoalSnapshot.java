package com.moneylog.core.goal.domain;

import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

/** Instantâneo mensal da medida de uma meta (ADR-016): um por meta e mês, atualizado até o mês fechar. */
public record GoalSnapshot(UUID goalId, Competence month, Money value, Instant capturedAt) {
}
