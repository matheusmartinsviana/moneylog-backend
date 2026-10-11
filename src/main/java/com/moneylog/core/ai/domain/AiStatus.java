package com.moneylog.core.ai.domain;

public enum AiStatus {
    COMPLETED, FAILED, DENIED, REJECTED_UNGROUNDED, BUDGET_EXCEEDED;

    public boolean calledProvider() {
        return this == COMPLETED || this == FAILED || this == REJECTED_UNGROUNDED;
    }
}
