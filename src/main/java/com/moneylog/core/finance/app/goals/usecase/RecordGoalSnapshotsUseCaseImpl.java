package com.moneylog.core.finance.app.goals.usecase;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.GoalReadModel;
import com.moneylog.core.finance.domain.GoalMetricValues;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalMetric;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class RecordGoalSnapshotsUseCaseImpl implements RecordGoalSnapshotsUseCase {

    private final GoalGateway goals;
    private final FinancialRecordsGateway records;
    private final CommitmentSourcesGateway sources;
    private final TimeProvider time;
    private final GoalReadModel model;

    public RecordGoalSnapshotsUseCaseImpl(GoalGateway goals, FinancialRecordsGateway records, CommitmentSourcesGateway sources,
                                          TimeProvider time, GoalReadModel model) {
        this.goals = goals;
        this.records = records;
        this.sources = sources;
        this.time = time;
        this.model = model;
    }

    @Override
    public int handle(UUID userId) {
        List<Goal> tracked = goals.findAll(userId).stream().map(Versioned::value).filter(GoalReadModel::isTracked).toList();
        if (tracked.isEmpty()) {
            return 0;
        }
        Set<GoalMetric> needed = EnumSet.noneOf(GoalMetric.class);
        tracked.forEach(g -> needed.add(g.isMetric() ? g.metric() : GoalMetric.TOTAL_DEBT));
        Map<GoalMetric, Money> metrics = GoalMetricValues.compute(needed, records.load(userId), () -> sources.recurrences(userId), time.today());
        tracked.forEach(goal -> model.recordSnapshot(userId, goal, GoalReadModel.valueOf(goal, metrics)));
        return tracked.size();
    }
}
