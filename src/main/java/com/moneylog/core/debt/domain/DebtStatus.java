package com.moneylog.core.debt.domain;

/**
 * Status da dívida, <b>derivado</b> (S-41): {@code VOIDED}/{@code CANCELED} pelo encerramento; senão pelas quitações
 * ativas ({@code OPEN} = nenhuma, {@code PARTIALLY_SETTLED}, {@code SETTLED} = principal).
 */
public enum DebtStatus {
    OPEN,
    PARTIALLY_SETTLED,
    SETTLED,
    CANCELED,
    VOIDED
}
