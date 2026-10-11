package com.moneylog.core.goal.domain;

public enum GoalMetric {
    TOTAL_DEBT,
    CARD_DEBT,
    PEOPLE_DEBT,
    OTHER_DEBT,
    ACCOUNTS_BALANCE,
    AVAILABLE_BALANCE,
    FIXED_COSTS_MONTHLY;

    public boolean isDebt() {
        return this == TOTAL_DEBT || this == CARD_DEBT || this == PEOPLE_DEBT || this == OTHER_DEBT;
    }

    public boolean isBalance() {
        return this == ACCOUNTS_BALANCE || this == AVAILABLE_BALANCE;
    }
}
