package com.moneylog.core.finance.domain;

import java.time.LocalDate;

/** Faixa de vencimento calculada no backend, no fuso do usuário (D2 §4.4). */
public enum Bucket {
    OVERDUE,
    TODAY,
    TOMORROW,
    NEXT_7_DAYS,
    NEXT_30_DAYS,
    LATER;

    public static Bucket of(LocalDate dueDate, LocalDate today) {
        if (dueDate.isBefore(today)) {
            return OVERDUE;
        }
        if (dueDate.equals(today)) {
            return TODAY;
        }
        if (dueDate.equals(today.plusDays(1))) {
            return TOMORROW;
        }
        if (!dueDate.isAfter(today.plusDays(7))) {
            return NEXT_7_DAYS;
        }
        return dueDate.isAfter(today.plusDays(30)) ? LATER : NEXT_30_DAYS;
    }
}
