package com.moneylog.core.finance.app.goals.usecase;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.app.goals.GoalReadModel;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalKind;
import com.moneylog.core.goal.domain.GoalMetric;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotFoundException;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class AnchorDebtPayoffBaselineUseCaseImpl implements AnchorDebtPayoffBaselineUseCase {

    private final GoalGateway goals;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;
    private final GoalReadModel model;

    public AnchorDebtPayoffBaselineUseCaseImpl(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time, GoalReadModel model) {
        this.goals = goals;
        this.records = records;
        this.time = time;
        this.model = model;
    }

    @Override
    public GoalDetails handle(AnchorDebtPayoffBaselineCommand command) {
        goals.lockForUpdate(command.userId(), command.goalId());
        Goal goal = goals.findById(command.userId(), command.goalId()).map(Versioned::value)
                .orElseThrow(() -> new GoalNotFoundException(command.goalId()));
        FinancialRecords loaded = records.load(command.userId());
        // a mesma fonte das leituras (GET /v1/debts/overview, balances do dashboard, recurrences/summary): a baseline e o progresso saem da mesma conta
        GoalMetric metric = goal.kind() == GoalKind.METRIC ? goal.metric() : GoalMetric.TOTAL_DEBT;
        Money value = model.metricValue(command.userId(), metric, loaded);
        goal.anchorBaseline(value, time.now());
        Versioned<Goal> saved = goals.save(goal);
        model.recordSnapshot(command.userId(), goal, value);
        return model.view(command.userId(), saved, loaded);
    }
}
