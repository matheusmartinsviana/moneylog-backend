package com.moneylog.core.finance.app.goals.usecase;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.app.goals.GoalReadModel;
import com.moneylog.core.finance.app.goals.GoalViews;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalKind;
import com.moneylog.core.goal.exception.GoalExceptions.GoalMetricDuplicateException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotFoundException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class UpdateGoalUseCaseImpl implements UpdateGoalUseCase {

    private final GoalGateway goals;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;
    private final GoalReadModel model;

    public UpdateGoalUseCaseImpl(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time, GoalReadModel model) {
        this.model = model;
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
        // campos inaplicáveis não são exigidos: sem alvo, quitar dívidas e medida mantêm o atual; quitar dívidas sem nome mantém o nome
        String name = c.name() == null && goal.isDebtPayoff() ? goal.name() : c.name();
        goal.update(name, c.target() == null && goal.kind() != GoalKind.SAVINGS ? goal.target() : c.target(), c.deadline(),
                c.priority() == null ? goal.priority() : c.priority(), c.description());
        if (goal.isMetric() && goals.findAll(c.userId()).stream().map(Versioned::value)
                .anyMatch(g -> g.isMetric() && !g.isArchived() && !g.id().equals(goal.id()) && g.metric() == goal.metric()
                        && g.target().equals(goal.target()))) {
            throw new GoalMetricDuplicateException();
        }
        goal.declarePlanNote(c.planNote());
        Versioned<Goal> saved = goals.save(goal);
        return model.view(c.userId(), saved, records.load(c.userId()));
    }
}
