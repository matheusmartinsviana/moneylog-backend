package com.moneylog.core.insights.domain;

import java.util.Objects;

public record InsufficientData(String type, Reason reason, Integer missing) {

    public InsufficientData {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reason, "reason");
    }

    public enum Reason {
        HISTORY,
        NO_INCOME,
        PARTIAL_MONTH,
        NO_DATA,
        NO_BASELINE,
        NO_ACCOUNTS,
        NO_SCHEDULE,
        NO_LIMIT,
        NO_PLAN,
        FUTURE_MONTH
    }
}
