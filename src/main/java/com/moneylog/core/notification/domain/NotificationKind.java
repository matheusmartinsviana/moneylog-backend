package com.moneylog.core.notification.domain;

public enum NotificationKind {
    INVOICE_DUE,
    DEBT_COLLECT,
    DEBT_PAYABLE_DUE,
    RECURRENCE_PENDING,
    COMMITTED_NEXT_MONTH,
    DAILY_SUMMARY,
    REMINDER,
    GOAL_DEADLINE,
    GROUPED;

    public boolean configurable() {
        return this != GROUPED && this != DAILY_SUMMARY && this != REMINDER;
    }
}
