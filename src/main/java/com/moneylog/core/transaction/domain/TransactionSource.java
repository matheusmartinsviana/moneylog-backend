package com.moneylog.core.transaction.domain;

/** Como a movimentação nasceu (D1 §2.4). */
public enum TransactionSource {
    MANUAL,
    IMPORT,
    RECURRENCE,
    /** Compra parcelada em andamento (ADR-013): só as parcelas restantes, sem consumo retroativo. */
    ONGOING,
    /** Item de fatura histórica (ADR-014): CARD −X / EQUITY +X numa fatura passada, sem consumo, para registrar o pagamento real dela. */
    HISTORICAL
}
