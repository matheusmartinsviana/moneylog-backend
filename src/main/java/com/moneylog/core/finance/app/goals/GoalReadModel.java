package com.moneylog.core.finance.app.goals;

import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.domain.CashPosition;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.GoalMetricValues;
import com.moneylog.core.finance.domain.GoalReserves;
import com.moneylog.core.goal.app.gateways.GoalSnapshotGateway;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalKind;
import com.moneylog.core.goal.domain.GoalMetric;
import com.moneylog.core.goal.domain.GoalSnapshot;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.TimeProvider;

public class GoalReadModel {

    private final CommitmentSourcesGateway sources;
    private final GoalSnapshotGateway snapshots;
    private final TimeProvider time;

    public GoalReadModel(CommitmentSourcesGateway sources, GoalSnapshotGateway snapshots, TimeProvider time) {
        this.sources = sources;
        this.snapshots = snapshots;
        this.time = time;
    }

    public GoalDetails view(UUID userId, Versioned<Goal> goal, FinancialRecords records) {
        return GoalViews.of(goal, context(userId, List.of(goal), records, false));
    }

    public GoalViews.Context context(UUID userId, Collection<Versioned<Goal>> goals, FinancialRecords records, boolean touch) {
        LocalDate today = time.today();
        Set<GoalMetric> needed = EnumSet.noneOf(GoalMetric.class);
        boolean anySavings = false;
        for (Versioned<Goal> versioned : goals) {
            Goal goal = versioned.value();
            if (goal.kind() == GoalKind.DEBT_PAYOFF) {
                needed.add(GoalMetric.TOTAL_DEBT);
            } else if (goal.kind() == GoalKind.METRIC && !goal.isArchived()) {
                needed.add(goal.metric());
            } else if (goal.kind() == GoalKind.SAVINGS) {
                anySavings = true;
            }
        }
        Map<GoalMetric, Money> metrics = GoalMetricValues.compute(needed, records, () -> sources.recurrences(userId), today);
        GoalReserves reserves = anySavings ? GoalReserves.at(records, CashPosition.at(records, records.ledger(), today)) : null;

        List<Versioned<Goal>> tracked = goals.stream().filter(v -> isTracked(v.value())).toList();
        Map<UUID, Map<Competence, Money>> byGoal = new HashMap<>();
        if (!tracked.isEmpty()) {
            Map<UUID, List<GoalSnapshot>> stored = snapshots.findByGoals(userId, tracked.stream().map(v -> v.value().id()).toList());
            stored.forEach((goalId, list) -> {
                Map<Competence, Money> months = new HashMap<>();
                list.forEach(s -> months.put(s.month(), s.value()));
                byGoal.put(goalId, months);
            });
            if (touch) {
                Competence month = Competence.from(today);
                for (Versioned<Goal> versioned : tracked) {
                    Goal goal = versioned.value();
                    if (!byGoal.getOrDefault(goal.id(), Map.of()).containsKey(month)) {
                        snapshots.touchIfAbsent(userId, goal.id(), month, valueOf(goal, metrics), time.now());
                    }
                }
            }
        }
        return new GoalViews.Context(GoalViews.accountNames(records), reserves, metrics, byGoal, today, time.zone());
    }

    public Money metricValue(UUID userId, GoalMetric metric, FinancialRecords records) {
        return GoalMetricValues.compute(EnumSet.of(metric), records, () -> sources.recurrences(userId), time.today())
                .getOrDefault(metric, Money.zero(CurrencyCode.BRL));
    }

    public void recordSnapshot(UUID userId, Goal goal, Money value) {
        snapshots.upsert(userId, goal.id(), Competence.from(time.today()), value, time.now());
    }

    public static boolean isTracked(Goal goal) {
        return !goal.isArchived() && (goal.kind() == GoalKind.METRIC || goal.kind() == GoalKind.DEBT_PAYOFF && goal.isBaselineSet());
    }

    public static Money valueOf(Goal goal, Map<GoalMetric, Money> metrics) {
        GoalMetric metric = goal.kind() == GoalKind.METRIC ? goal.metric() : GoalMetric.TOTAL_DEBT;
        return metrics.getOrDefault(metric, Money.zero(CurrencyCode.BRL));
    }
}
