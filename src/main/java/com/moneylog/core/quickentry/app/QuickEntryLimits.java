package com.moneylog.core.quickentry.app;

import com.moneylog.core.sk.money.Money;

public record QuickEntryLimits(Money maxAmount, int maxEntriesPerDay, Money maxExpensePerDay, Money maxIncomePerDay) {

    public QuickEntryLimits {
        maxAmount = positiveOrNull(maxAmount);
        maxExpensePerDay = positiveOrNull(maxExpensePerDay);
        maxIncomePerDay = positiveOrNull(maxIncomePerDay);
        maxEntriesPerDay = Math.max(maxEntriesPerDay, 0);
    }

    public static QuickEntryLimits none() {
        return new QuickEntryLimits(null, 0, null, null);
    }

    public boolean hasDailyLimit() {
        return maxEntriesPerDay > 0 || maxExpensePerDay != null || maxIncomePerDay != null;
    }

    private static Money positiveOrNull(Money value) {
        return value == null || !value.isPositive() ? null : value;
    }
}
