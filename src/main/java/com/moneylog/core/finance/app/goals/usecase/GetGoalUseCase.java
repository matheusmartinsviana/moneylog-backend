package com.moneylog.core.finance.app.goals.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;

public interface GetGoalUseCase {

    GoalDetails handle(GetGoalQuery query);

    record GetGoalQuery(UUID userId, UUID goalId) {
    }
}
