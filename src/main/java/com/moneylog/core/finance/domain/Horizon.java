package com.moneylog.core.finance.domain;

import java.time.LocalDate;

/** Horizontes cumulativos dos compromissos (D1 §2.8): 7, 30, 60 e 90 dias, 6 e 12 meses a partir de hoje. */
public enum Horizon {
    D7(7, 0),
    D30(30, 0),
    D60(60, 0),
    D90(90, 0),
    M6(0, 6),
    M12(0, 12);

    private final int days;
    private final int months;

    Horizon(int days, int months) {
        this.days = days;
        this.months = months;
    }

    /** Último dia (inclusive) do horizonte: hoje + H. */
    public LocalDate endFrom(LocalDate today) {
        return today.plusDays(days).plusMonths(months);
    }
}
