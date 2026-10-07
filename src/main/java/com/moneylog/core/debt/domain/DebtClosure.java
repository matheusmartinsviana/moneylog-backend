package com.moneylog.core.debt.domain;

/** Único estado gravado da dívida: o encerramento por perdão ou por estorno do registro (S-41). */
public enum DebtClosure {
    CANCELED,
    VOIDED
}
