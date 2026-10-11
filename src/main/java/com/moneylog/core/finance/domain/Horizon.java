package com.moneylog.core.finance.domain;

import java.time.LocalDate;

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

    public LocalDate endFrom(LocalDate today) {
        return today.plusDays(days).plusMonths(months);
    }
}
