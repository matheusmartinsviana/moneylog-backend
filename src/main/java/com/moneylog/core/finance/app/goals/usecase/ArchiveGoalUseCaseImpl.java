package com.moneylog.core.finance.app.goals.usecase;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.app.goals.GoalViews;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotFoundException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class ArchiveGoalUseCaseImpl implements ArchiveGoalUseCase {

    private final GoalGateway goals;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public ArchiveGoalUseCaseImpl(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time) {
        this.goals = goals;
        this.records = records;
        this.time = time;
    }

    @Override
    public GoalDetails handle(ArchiveGoalCommand command) {
        goals.lockForUpdate(command.userId(), command.goalId());
        Versioned<Goal> current = goals.findById(command.userId(), command.goalId())
                .orElseThrow(() -> new GoalNotFoundException(command.goalId()));
        Goal goal = current.value();
        goal.archive();
        Versioned<Goal> saved = goals.save(goal);
        return GoalViews.of(saved, records.load(command.userId()), time.today());
    }
}
