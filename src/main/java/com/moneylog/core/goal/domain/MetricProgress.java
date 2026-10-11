package com.moneylog.core.goal.domain;

import java.math.BigDecimal;

import com.moneylog.core.sk.money.Money;

public record MetricProgress(Money current, Money remaining, BigDecimal ratio, boolean reached) {
}
