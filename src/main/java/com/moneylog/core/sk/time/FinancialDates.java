package com.moneylog.core.sk.time;

import java.time.LocalDate;

import com.moneylog.core.sk.exception.BusinessRuleException;

/**
 * Janela de datas aceitas para datas financeiras informadas pelo usuário (B4, suposição S-114): de 2000-01-01 a
 * 2100-12-31. Fora dela o Postgres e o calendário de competências falham (ano 0, +999999999), e nenhuma movimentação
 * real cai ali.
 */
public final class FinancialDates {

    public static final LocalDate MIN = LocalDate.of(2000, 1, 1);
    public static final LocalDate MAX = LocalDate.of(2100, 12, 31);

    private FinancialDates() {
    }

    public static boolean isInWindow(LocalDate date) {
        return date != null && !date.isBefore(MIN) && !date.isAfter(MAX);
    }

    /** Devolve a própria data ou lança {@link DateOutOfRangeException} ({@code null} passa: a obrigatoriedade é de outro guarda). */
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
