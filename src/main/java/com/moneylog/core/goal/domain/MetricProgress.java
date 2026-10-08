package com.moneylog.core.goal.domain;

import java.math.BigDecimal;

import com.moneylog.core.sk.money.Money;

/**
 * Progresso de uma meta {@link GoalKind#METRIC} (ADR-016). {@code ratio} fica em 0..1 com 4 casas e só chega a 1,0000 quando
 * {@code reached}; {@code remaining} é a distância até o alvo (nunca negativa).
 */
public record MetricProgress(Money current, Money remaining, BigDecimal ratio, boolean reached) {
}
