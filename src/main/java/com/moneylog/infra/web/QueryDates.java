package com.moneylog.infra.web;

import java.time.LocalDate;

import com.moneylog.core.sk.time.FinancialDates;
import com.moneylog.infra.exception.RequestValidationException;

public final class QueryDates {

    public static final int MAX_WINDOW_YEARS = 3;

    private QueryDates() {
    }

    public static LocalDate inWindow(LocalDate date, String field) {
        if (date != null && !FinancialDates.isInWindow(date)) {
            throw new RequestValidationException(field, "DATE_OUT_OF_RANGE",
                    "'" + field + "' deve estar entre " + FinancialDates.MIN + " e " + FinancialDates.MAX + ".");
        }
        return date;
    }

    public static void range(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new RequestValidationException("from", "INVALID_RANGE", "'from' não pode ser depois de 'to'.");
        }
        if (to.isAfter(from.plusYears(MAX_WINDOW_YEARS))) {
            throw new RequestValidationException("to", "INVALID_RANGE",
                    "a janela entre 'from' e 'to' pode ter no máximo " + MAX_WINDOW_YEARS + " anos.");
        }
    }
}
