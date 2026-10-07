package com.moneylog.core.finance.domain;

/** Lente da visão de lançamentos por competência (D1 §2.10). */
public enum EntryLens {
    /** Livro {@code EXPENSE}: consumo (reembolsos aparecem negativos). */
    CONSUMPTION,
    /** Livro {@code INCOME}: receita (com sinal invertido, positiva). */
    INCOME
}
