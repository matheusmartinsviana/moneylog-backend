package com.moneylog.core.finance.app.goals.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;

public interface UnarchiveGoalUseCase {

    GoalDetails handle(UnarchiveGoalCommand command);

    record UnarchiveGoalCommand(UUID userId, UUID goalId) {
    }
}
