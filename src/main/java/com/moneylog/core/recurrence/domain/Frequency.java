package com.moneylog.core.recurrence.domain;

/** Frequência da recorrência (D1 §2.7). {@code CUSTOM} = a cada {@code n} dias, semanas ou meses. */
public enum Frequency {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
    CUSTOM
}
