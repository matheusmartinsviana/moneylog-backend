package com.moneylog.core.finance.app.goals.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;

public interface GetGoalUseCase {

    /** A meta com valor atual, progresso, estimativa mensal e reserva descoberta; de outro usuário → {@code GOAL_NOT_FOUND}. */
    GoalDetails handle(GetGoalQuery query);

    record GetGoalQuery(UUID userId, UUID goalId) {
    }
}
