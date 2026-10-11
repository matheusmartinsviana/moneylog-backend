package com.moneylog.infra.summary.controller;

import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.finance.app.summary.MonthlySummaryView;
import com.moneylog.core.finance.domain.MonthlySummary;
import com.moneylog.core.sk.money.Money;

public record MonthlySummaryResponse(String competence, boolean partial, @JsonProperty("isProjection") boolean isProjection, Money income,
                                     Money expenses, Money investments, Money net, String savingsRate, LargestCategory largestCategory,
                                     LargestExpense largestExpense, CardSummary card, Money futureInstallments, Comparison comparison) {

    public record CategoryRef(UUID id, String name) {
    }

    public record LargestCategory(CategoryRef category, Money amount) {
    }

    public record LargestExpense(UUID transactionId, UUID debtId, String description, CategoryRef category, LocalDate date, Money amount,
                                 Integer installmentNumber, Integer installmentTotal) {
    }

    public record CardSummary(Money consumption, Money outstanding) {
    }

    public record Delta(Money amount, String percent) {
    }

    public record Totals(String competence, Money income, Money expenses, Money investments, Money net, String savingsRate) {
    }

    public record Comparison(Totals previousMonth, Delta incomeDelta, Delta expensesDelta, Delta investmentsDelta, Delta netDelta,
                             String savingsRateDelta) {
    }

    public static MonthlySummaryResponse of(MonthlySummaryView view) {
        MonthlySummary s = view.summary();
        LargestCategory largestCategory = s.largestCategory() == null ? null
                : new LargestCategory(ref(s.largestCategory().categoryId(), view.largestCategory()), s.largestCategory().amount());
        MonthlySummary.LargestExpense e = s.largestExpense();
        LargestExpense largestExpense = e == null ? null
                : new LargestExpense(e.transactionId(), e.debtId(), e.description(), ref(e.categoryId(), view.largestExpenseCategory()),
                e.date(), e.amount(), e.installmentNumber(), e.installmentTotal());
        MonthlySummary.Comparison c = s.comparison();
        MonthlySummary.Totals p = c.previousTotals();
        Comparison comparison = new Comparison(
                new Totals(c.previous().toString(), p.income(), p.expenses(), p.investments(), p.net(), ratio(p.savingsRate())),
                delta(c.income()), delta(c.expenses()), delta(c.investments()), delta(c.net()), ratio(c.savingsRateDelta()));
        return new MonthlySummaryResponse(s.competence().toString(), s.partial(), s.isProjection(), s.income(), s.expenses(),
                s.investments(), s.net(), s.savingsRate() == null ? null : s.savingsRate().toPlainString(), largestCategory, largestExpense,
                new CardSummary(s.card().consumption(), s.card().outstanding()), s.futureInstallments(), comparison);
    }

    private static CategoryRef ref(UUID id, Category category) {
        return new CategoryRef(id, category == null ? null : category.name());
    }

    private static String ratio(java.math.BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static Delta delta(MonthlySummary.Delta d) {
        return new Delta(d.amount(), d.percent() == null ? null : d.percent().toPlainString());
    }
}
