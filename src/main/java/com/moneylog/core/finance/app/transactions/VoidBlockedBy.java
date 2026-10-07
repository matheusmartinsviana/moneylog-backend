package com.moneylog.core.finance.app.transactions;

/** Por que uma movimentação não pode ser estornada por regra (S-88, S-89). */
public enum VoidBlockedBy {
    ACCOUNT_ARCHIVED,
    HAS_ACTIVE_REFUNDS,
    /** Compra ou crédito no cartão com parcela/crédito em fatura fechada (S-36, S-122): cancele as parcelas e reembolse. */
    TRANSACTION_IN_CLOSED_INVOICE,
    /** Origem de dívida ({@code LOAN_GIVEN}, captação, despesa dividida) com quitação ativa: estorne antes as quitações (S-36, S-141). */
    DEBT_HAS_SETTLEMENTS,
    /** A dívida foi perdoada (ou já está encerrada): nem a origem nem a quitação se estornam (S-41, S-141). */
    DEBT_CLOSED
}
