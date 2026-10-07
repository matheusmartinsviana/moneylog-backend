package com.moneylog.core.sk.ledger;

/**
 * Origem de um posting (D1 §1.1, S-45). Nem todo posting vem de uma movimentação.
 */
public enum PostingSourceType {
    /** Gerado por uma {@code Transaction} (ou por uma parcela dela, com {@code installmentId}). */
    TRANSACTION,
    /** Saldo inicial da conta. */
    ACCOUNT_OPENING,
    /** Dívida sem movimentação ({@code WITHOUT_MOVEMENT}). */
    DEBT_RECOGNITION,
    /** Perdão de dívida ({@code Debt.cancel}). */
    DEBT_CANCELLATION
}
