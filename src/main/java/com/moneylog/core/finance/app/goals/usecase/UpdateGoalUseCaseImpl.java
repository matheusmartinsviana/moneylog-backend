package com.moneylog.core.finance.app.goals.usecase;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.app.goals.GoalViews;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotFoundException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class UpdateGoalUseCaseImpl implements UpdateGoalUseCase {

    private final GoalGateway goals;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public UpdateGoalUseCaseImpl(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time) {
        this.goals = goals;
        this.records = records;
        this.time = time;
    }

    @Override
    public GoalDetails handle(UpdateGoalCommand c) {
        goals.lockForUpdate(c.userId(), c.goalId());
        Versioned<Goal> current = goals.findById(c.userId(), c.goalId()).orElseThrow(() -> new GoalNotFoundException(c.goalId()));
        if (current.version() != c.version()) {
            throw new VersionConflictException(c.version(), current.version());
        }
        Goal goal = current.value();
        goal.update(c.name(), c.target(), c.deadline(),
                c.priority() == null ? goal.priority() : c.priority(), c.description());
        Versioned<Goal> saved = goals.save(goal);
        return GoalViews.of(saved, records.load(c.userId()), time.today());
    }
}
