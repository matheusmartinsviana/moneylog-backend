package com.moneylog.core.finance.app.goals.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;

public interface ArchiveGoalUseCase {

    GoalDetails handle(ArchiveGoalCommand command);

    record ArchiveGoalCommand(UUID userId, UUID goalId) {
    }
}
