package com.moneylog.core.ai.domain;

/** Estado final de uma análise registrada (S-183). */
public enum AiStatus {
    COMPLETED, FAILED, DENIED, REJECTED_UNGROUNDED, BUDGET_EXCEEDED;

    /** Houve chamada ao provedor (conta no limite diário). */
    public boolean calledProvider() {
        return this == COMPLETED || this == FAILED || this == REJECTED_UNGROUNDED;
    }
}
