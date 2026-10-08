package com.moneylog.core.finance.app.goals.usecase;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.app.goals.GoalReadModel;
import com.moneylog.core.finance.app.goals.GoalViews;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotFoundException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class UnarchiveGoalUseCaseImpl implements UnarchiveGoalUseCase {

    private final GoalGateway goals;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;
    private final GoalReadModel model;

    public UnarchiveGoalUseCaseImpl(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time, GoalReadModel model) {
        this.model = model;
        this.goals = goals;
        this.records = records;
        this.time = time;
    }

    @Override
    public GoalDetails handle(UnarchiveGoalCommand command) {
        goals.lockForUpdate(command.userId(), command.goalId());
        Versioned<Goal> current = goals.findById(command.userId(), command.goalId())
                .orElseThrow(() -> new GoalNotFoundException(command.goalId()));
        Goal goal = current.value();
        if (goal.isDebtPayoff() && goals.findAll(command.userId()).stream().map(Versioned::value)
                .anyMatch(g -> g.isDebtPayoff() && !g.isArchived() && !g.id().equals(goal.id()))) {
            throw new com.moneylog.core.goal.exception.GoalExceptions.DebtPayoffGoalExistsException();
        }
        if (goal.isMetric() && goals.findAll(command.userId()).stream().map(Versioned::value)
                .anyMatch(g -> g.isMetric() && !g.isArchived() && !g.id().equals(goal.id()) && g.metric() == goal.metric()
                        && g.target().equals(goal.target()))) {
            throw new com.moneylog.core.goal.exception.GoalExceptions.GoalMetricDuplicateException();
        }
        goal.unarchive();
        Versioned<Goal> saved = goals.save(goal);
        return model.view(command.userId(), saved, records.load(command.userId()));
    }
}
