package com.moneylog.core.sk.time;

import java.time.LocalDate;

import com.moneylog.core.sk.exception.BusinessRuleException;

public final class FinancialDates {

    public static final LocalDate MIN = LocalDate.of(2000, 1, 1);
    public static final LocalDate MAX = LocalDate.of(2100, 12, 31);

    private FinancialDates() {
    }

    public static boolean isInWindow(LocalDate date) {
        return date != null && !date.isBefore(MIN) && !date.isAfter(MAX);
    }

    public static LocalDate requireInWindow(LocalDate date, String field) {
        if (date != null && !isInWindow(date)) {
            throw new DateOutOfRangeException(field);
        }
        return date;
    }

    public static class DateOutOfRangeException extends BusinessRuleException {
        public DateOutOfRangeException(String field) {
            super("DATE_OUT_OF_RANGE", "A data de '" + field + "' deve estar entre " + MIN + " e " + MAX + ".");
        }
    }
}
