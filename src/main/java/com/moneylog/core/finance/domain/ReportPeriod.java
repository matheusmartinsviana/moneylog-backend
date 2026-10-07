package com.moneylog.core.finance.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Período relativo ao dia do usuário (o mesmo {@code today} do dashboard) da série de entradas e saídas (redesign, S-284):
 * {@code D7} = de hoje−6 a hoje e {@code D30} = de hoje−29 a hoje (um ponto por dia); {@code M3} = de hoje−3 meses (mesmo dia, limitado ao
 * último dia do mês) a hoje (um ponto por semana, de segunda a domingo, recortado na janela); {@code M12} = as 12 competências que terminam na
 * corrente (um ponto por mês).
 */
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

    /** Primeiro dia da janela. Em {@code M12}, o primeiro dia da competência mais antiga. */
    public LocalDate from(LocalDate today) {
        return switch (this) {
            case D7 -> today.minusDays(6);
            case D30 -> today.minusDays(29);
            case M3 -> today.minusMonths(3);
            case M12 -> today.minusMonths(11).withDayOfMonth(1);
        };
    }

    /** Último dia da janela: hoje, e em {@code M12} o último dia da competência corrente (o mês é inteiro, como no dashboard). */
    public LocalDate to(LocalDate today) {
        return this == M12 ? today.withDayOfMonth(today.lengthOfMonth()) : today;
    }

    static LocalDate mondayOf(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
    }
}
