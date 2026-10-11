package com.moneylog.core.finance.app.goals.usecase;

import java.util.UUID;
import java.util.function.Consumer;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.app.goals.GoalReadModel;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCases.AddGoalActionUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCases.CheckInGoalActionUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCases.DeleteGoalActionUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCases.UpdateGoalActionUseCase;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotFoundException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class GoalActionUseCasesImpl implements AddGoalActionUseCase, UpdateGoalActionUseCase, DeleteGoalActionUseCase, CheckInGoalActionUseCase {

    private final GoalGateway goals;
    private final FinancialRecordsGateway records;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final GoalReadModel model;

    public GoalActionUseCasesImpl(GoalGateway goals, FinancialRecordsGateway records, IdGenerator ids, TimeProvider time, GoalReadModel model) {
        this.goals = goals;
        this.records = records;
        this.ids = ids;
        this.time = time;
        this.model = model;
    }

    @Override
    public GoalDetails handle(AddGoalActionCommand c) {
        return change(c.userId(), c.goalId(), goal -> goal.addAction(ids.next(), c.title(), c.frequency(), time.now()));
    }

    @Override
    public GoalDetails handle(UpdateGoalActionCommand c) {
        return change(c.userId(), c.goalId(), goal -> goal.updateAction(c.actionId(), c.title(), c.frequency()));
    }

    @Override
    public GoalDetails handle(DeleteGoalActionCommand c) {
        return change(c.userId(), c.goalId(), goal -> goal.removeAction(c.actionId()));
    }

    @Override
    public GoalDetails handle(CheckInGoalActionCommand c) {
        return change(c.userId(), c.goalId(), goal -> goal.checkIn(c.actionId(), c.period(), c.done()));
    }

    private GoalDetails change(UUID userId, UUID goalId, Consumer<Goal> mutation) {
        goals.lockForUpdate(userId, goalId);
        Goal goal = goals.findById(userId, goalId).map(Versioned::value).orElseThrow(() -> new GoalNotFoundException(goalId));
        mutation.accept(goal);
        Versioned<Goal> saved = goals.save(goal);
        return model.view(userId, saved, records.load(userId));
    }
}
