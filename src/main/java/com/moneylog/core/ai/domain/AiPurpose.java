package com.moneylog.core.ai.domain;

public enum AiPurpose {
    CATEGORIZE_IMPORT(false),
    CATEGORIZE_TRANSACTIONS(false),
    ANALYZE_STATEMENT(false),
    MONTHLY_SUMMARY(true),
    INSIGHTS(true),
    FORECAST(false),
    DAILY_MESSAGE(false);

    private final boolean supported;

    AiPurpose(boolean supported) {
        this.supported = supported;
    }

    public boolean supported() {
        return supported;
    }
}
