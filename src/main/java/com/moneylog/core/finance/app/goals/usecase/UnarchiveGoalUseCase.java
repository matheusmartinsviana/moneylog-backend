package com.moneylog.core.finance.app.goals.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;

public interface UnarchiveGoalUseCase {

    /**
     * Desarquiva a meta: ela volta ao "reservado" (as reservas guardadas contam de novo) e a aceitar reserva e edição. Não arquivada →
     * {@code GOAL_NOT_ARCHIVED}.
     */
    GoalDetails handle(UnarchiveGoalCommand command);

    record UnarchiveGoalCommand(UUID userId, UUID goalId) {
    }
}
