package com.moneylog.core.importing.domain;

/** Ciclo do lote: revisão → aplicado → (parcialmente) desfeito. */
public enum ImportBatchStatus {
    PENDING_REVIEW,
    APPLIED,
    UNDONE,
    /** Desfeito, mas algumas movimentações não puderam ser estornadas (S-36): ficam para correção manual. */
    PARTIALLY_UNDONE
}
