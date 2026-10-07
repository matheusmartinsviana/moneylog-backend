package com.moneylog.infra.web;

import java.time.LocalDate;

import com.moneylog.core.sk.time.FinancialDates;
import com.moneylog.infra.exception.RequestValidationException;

/**
 * Validação das datas de <b>consulta</b> (query string) na fronteira web (BUG-QA-24, S-239): dentro da janela financeira 2000 a 2100
 * ({@link FinancialDates}), {@code from} não depois de {@code to} e janela de no máximo {@value #MAX_WINDOW_YEARS} anos. Tudo é
 * {@code 400 VALIDATION_FAILED}; datas extremas ({@code +999999999-12-31}) nunca chegam ao calendário nem ao Postgres.
 */
public final class QueryDates {

    /** Janela máxima entre {@code from} e {@code to} nas listas de compromissos e de ocorrências (S-240). */
    public static final int MAX_WINDOW_YEARS = 3;

    private QueryDates() {
    }

    /** A própria data, ou {@code 400 DATE_OUT_OF_RANGE} se fora de 2000-01-01 a 2100-12-31 ({@code null} passa). */
    public static LocalDate inWindow(LocalDate date, String field) {
        if (date != null && !FinancialDates.isInWindow(date)) {
            throw new RequestValidationException(field, "DATE_OUT_OF_RANGE",
                    "'" + field + "' deve estar entre " + FinancialDates.MIN + " e " + FinancialDates.MAX + ".");
        }
        return date;
    }

    /** {@code from <= to} e {@code to <= from + 3 anos} (as duas datas já efetivas, com o padrão aplicado). */
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
