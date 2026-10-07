package com.moneylog.core.transaction.domain;

/** Como a movimentação nasceu (D1 §2.4). */
public enum TransactionSource {
    MANUAL,
    IMPORT,
    RECURRENCE,
    /** Compra parcelada em andamento (ADR-013): só as parcelas restantes, sem consumo retroativo. */
    ONGOING
}
