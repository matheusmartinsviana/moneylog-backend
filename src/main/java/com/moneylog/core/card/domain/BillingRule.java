package com.moneylog.core.card.domain;

import java.time.YearMonth;
import java.util.Objects;

import com.moneylog.core.sk.time.DayOfMonthRule;

public record BillingRule(YearMonth effectiveFrom, DayOfMonthRule closingDay, DayOfMonthRule dueDay) {

    public BillingRule {
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(closingDay, "closingDay");
        Objects.requireNonNull(dueDay, "dueDay");
    }

    public boolean dueInSameMonth() {
        return dueDay.day() > closingDay.day();
    }
}
