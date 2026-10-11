package com.moneylog.core.finance.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;

public enum ReportPeriod {
    D7(IncomeExpenseSeries.Granularity.DAY),
    D30(IncomeExpenseSeries.Granularity.DAY),
    M3(IncomeExpenseSeries.Granularity.WEEK),
    M12(IncomeExpenseSeries.Granularity.MONTH);

    private final IncomeExpenseSeries.Granularity granularity;

    ReportPeriod(IncomeExpenseSeries.Granularity granularity) {
        this.granularity = granularity;
    }

    public IncomeExpenseSeries.Granularity granularity() {
        return granularity;
    }

    public LocalDate from(LocalDate today) {
        return switch (this) {
            case D7 -> today.minusDays(6);
            case D30 -> today.minusDays(29);
            case M3 -> today.minusMonths(3);
            case M12 -> today.minusMonths(11).withDayOfMonth(1);
        };
    }

    public LocalDate to(LocalDate today) {
        return this == M12 ? today.withDayOfMonth(today.lengthOfMonth()) : today;
    }

    static LocalDate mondayOf(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
    }
}
