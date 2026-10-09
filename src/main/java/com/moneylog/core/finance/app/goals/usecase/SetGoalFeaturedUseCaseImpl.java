package com.moneylog.core.finance.app.goals.usecase;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.app.goals.GoalReadModel;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.SetGoalFeaturedUseCase;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotFoundException;
import com.moneylog.core.sk.persistence.Versioned;

/**
 * Meta em destaque da Home (ADR-018): uma só por usuário. Marcar outra move o destaque: a anterior é desmarcada e gravada antes (o índice
 * único parcial não admite duas), tudo na mesma transação e sob a trava do usuário.
 */
public class SetGoalFeaturedUseCaseImpl implements SetGoalFeaturedUseCase {

    private final GoalGateway goals;
    private final FinancialRecordsGateway records;
    private final GoalReadModel model;

    public SetGoalFeaturedUseCaseImpl(GoalGateway goals, FinancialRecordsGateway records, GoalReadModel model) {
        this.goals = goals;
        this.records = records;
        this.model = model;
    }

    @Override
    public GoalDetails handle(SetGoalFeaturedCommand c) {
        goals.lockForUpdate(c.userId(), c.goalId());
        Goal goal = goals.findById(c.userId(), c.goalId()).map(Versioned::value).orElseThrow(() -> new GoalNotFoundException(c.goalId()));
        goals.lockUserMedia(c.userId());
        if (c.featured()) {
            goal.feature();
            goals.findFeatured(c.userId()).map(Versioned::value).filter(previous -> !previous.id().equals(goal.id())).ifPresent(previous -> {
                previous.unfeature();
                goals.save(previous);
            });
        } else {
            goal.unfeature();
        }
        Versioned<Goal> saved = goals.save(goal);
        return model.view(c.userId(), saved, records.load(c.userId()));
    }
}
