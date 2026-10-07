package com.moneylog.core.finance.domain;

/** Tipo de compromisso futuro (D1 §2.8). */
public enum CommitmentKind {
    /** Fatura fechada ({@code CLOSED}/{@code OVERDUE}) com saldo. */
    INVOICE,
    /** Previsão de fatura ({@code OPEN}/{@code FUTURE}) com saldo, já abatidos crédito transportado e antecipações. */
    INVOICE_FORECAST,
    /** Parcela: só aninhada numa fatura, informativa, nunca somada. */
    INSTALLMENT,
    DEBT_PAYABLE,
    DEBT_RECEIVABLE,
    /** Ocorrência prevista e não realizada de recorrência. */
    RECURRENCE,
    /**
     * Movimentação ativa com data futura que muda o caixa (S-258): entra no caixa na data dela; até lá é compromisso (nunca os
     * dois). Ocorrência de recorrência realizada adiantada vira esta, e a pendente sai.
     */
    SCHEDULED_TRANSACTION
}
