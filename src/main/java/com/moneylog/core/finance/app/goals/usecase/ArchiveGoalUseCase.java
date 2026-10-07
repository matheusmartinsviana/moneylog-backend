package com.moneylog.core.finance.app.goals.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;

public interface ArchiveGoalUseCase {

    /**
     * Arquiva a meta: ela sai do "reservado" (o dinheiro volta a ficar disponível) e não aceita reserva nem edição; as reservas
     * ficam guardadas. Já arquivada → {@code GOAL_ALREADY_ARCHIVED}.
     */
    GoalDetails handle(ArchiveGoalCommand command);

    record ArchiveGoalCommand(UUID userId, UUID goalId) {
    }
}
