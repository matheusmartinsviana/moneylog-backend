package com.moneylog.core.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public record DebtOverview(LocalDate asOf, Money total, Parts parts, Cards cards, DebtGroup people, DebtGroup other,
                           Money receivablesInfo, boolean hasRecords, Money fixedCostsMonthly, Income income, Liquidity liquidity,
                           Comparison comparison, Schedule schedule) {

    public record Parts(Money cards, Money people, Money other) {
    }

    public record Cards(Money total, Money invoiced, Money future, List<CardItem> items) {
    }

    public record CardItem(UUID cardId, String cardName, Money total, Money invoiced, Money future, Money otherCharges, Money adjustment,
                           List<Plan> plans) {
    }

    public record Plan(UUID purchaseId, String description, boolean ongoing, Money installmentAmount, int totalInstallments,
                       int remainingInstallments, int nextInstallmentNumber, Money remaining, LocalDate nextDueDate, LocalDate lastDueDate) {
    }

    public record DebtGroup(Money total, List<DebtItem> items) {
    }

    public record DebtItem(UUID debtId, UUID personId, String creditorName, String description, Money outstanding, LocalDate expectedDate,
                           boolean overdue) {
    }

    public enum IncomeSource {
        RECURRING_ESTIMATE,
        LAST_FULL_MONTH,
        NONE
    }

    public record Income(Money monthly, IncomeSource source) {
    }

    public record Liquidity(Money inAccounts, Money available) {
    }

    public enum ComparisonReason {
        NO_INCOME
    }

    public record Comparison(BigDecimal debtToIncome, BigDecimal fixedCostToIncome, Money debtMinusInAccounts, ComparisonReason reason) {
    }

    public enum PayoffReason {
        UNDATED_DEBTS,
        BEYOND_HORIZON
    }

    public record Schedule(int months, List<Point> points, Money overdue, Money undated, Money beyondHorizon, Competence payoffCompetence,
                           PayoffReason payoffReason) {
    }

    public record Point(Competence competence, Money cards, Money people, Money other, Money total, Money remainingAfter) {
    }
}
