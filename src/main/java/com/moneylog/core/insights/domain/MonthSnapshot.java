package com.moneylog.core.insights.domain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public record MonthSnapshot(Competence month, Money income, Money consumption, Money essential, Map<UUID, Money> byCategory) {

    public MonthSnapshot {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(income, "income");
        Objects.requireNonNull(consumption, "consumption");
        Objects.requireNonNull(essential, "essential");
        byCategory = Map.copyOf(byCategory);
    }

    public boolean isEmpty() {
        return income.isZero() && consumption.isZero();
    }

    public Money categoryFloorZero(UUID categoryId) {
        Money value = byCategory.get(categoryId);
        return value == null || !value.isPositive() ? Money.zero(consumption.currency()) : value;
    }
}
