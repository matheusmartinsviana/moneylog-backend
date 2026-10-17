package com.moneylog.infra.reports.controller;

import java.time.LocalDate;
import java.util.List;

import com.moneylog.core.finance.domain.ConsumptionView;
import com.moneylog.core.finance.domain.IncomeExpenseSeries;
import com.moneylog.core.sk.money.Money;

public record IncomeExpenseSeriesResponse(ConsumptionView view, String period, LocalDate from, LocalDate to, String basis, String granularity, List<Point> points,
                                          Totals totals) {

    public record Point(LocalDate periodStart, LocalDate periodEnd, boolean partial, Money income, Money expenses, Money net) {
    }

    public record Totals(Money income, Money expenses, Money net) {
    }

    public static IncomeExpenseSeriesResponse of(IncomeExpenseSeries s, ConsumptionView view) {
        return new IncomeExpenseSeriesResponse(view, s.period().name(), s.from(), s.to(), s.basis().name(), s.granularity().name(),
                s.points().stream().map(p -> new Point(p.periodStart(), p.periodEnd(), p.partial(), p.income(), p.expenses(), p.net())).toList(),
                new Totals(s.totals().income(), s.totals().expenses(), s.totals().net()));
    }
}
