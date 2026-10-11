package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.moneylog.core.finance.domain.DebtOverview.Parts;
import com.moneylog.core.goal.domain.GoalMetric;
import com.moneylog.core.recurrence.domain.MonthlyEstimate;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.money.Money;

public final class GoalMetricValues {

    private GoalMetricValues() {
    }

    public static Map<GoalMetric, Money> compute(Set<GoalMetric> needed, FinancialRecords records,
                                                 Supplier<Collection<RecurringTransaction>> recurrences, LocalDate today) {
        Map<GoalMetric, Money> values = new EnumMap<>(GoalMetric.class);
        if (needed.isEmpty()) {
            return values;
        }
        Collection<RecurringTransaction> recurring = null;
        if (needed.stream().anyMatch(m -> m.isDebt())) {
            Parts parts = DebtOverviewCalculator.parts(records, today);
            Money total = parts.cards().add(parts.people()).add(parts.other());
            put(values, needed, GoalMetric.TOTAL_DEBT, total);
            put(values, needed, GoalMetric.CARD_DEBT, parts.cards());
            put(values, needed, GoalMetric.PEOPLE_DEBT, parts.people());
            put(values, needed, GoalMetric.OTHER_DEBT, parts.other());
        }
        boolean fixed = needed.contains(GoalMetric.FIXED_COSTS_MONTHLY);
        if (fixed || needed.stream().anyMatch(GoalMetric::isBalance)) {
            recurring = recurrences.get();
        }
        if (needed.stream().anyMatch(GoalMetric::isBalance)) {
            DashboardSummary dashboard = DashboardSummary.of(records, recurring, today, today);
            put(values, needed, GoalMetric.ACCOUNTS_BALANCE, dashboard.balances().inAccounts());
            put(values, needed, GoalMetric.AVAILABLE_BALANCE, dashboard.balances().available());
        }
        if (fixed) {
            values.put(GoalMetric.FIXED_COSTS_MONTHLY, MonthlyEstimate.of(recurring, today).fixedCosts());
        }
        return values;
    }

    private static void put(Map<GoalMetric, Money> values, Set<GoalMetric> needed, GoalMetric metric, Money value) {
        if (needed.contains(metric)) {
            values.put(metric, value);
        }
    }
}
