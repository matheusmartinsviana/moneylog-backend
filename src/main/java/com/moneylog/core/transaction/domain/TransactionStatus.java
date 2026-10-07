package com.moneylog.core.transaction.domain;

/** Única máquina de estados gravada da movimentação: {@code ACTIVE → VOIDED} (estorno com motivo e guarda). */
public enum TransactionStatus {
    ACTIVE,
    VOIDED
}
