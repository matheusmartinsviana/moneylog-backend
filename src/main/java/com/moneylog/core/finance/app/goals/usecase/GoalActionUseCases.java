package com.moneylog.core.finance.app.goals.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.goal.domain.GoalActionFrequency;

/**
 * Ações do plano declarado da meta (ADR-016). Todas devolvem a meta atualizada; não movem dinheiro nem tocam o razão. Meta arquivada não
 * aceita mudança ({@code GOAL_ARCHIVED}); ação de outra meta ou inexistente é {@code GOAL_ACTION_NOT_FOUND}.
 */
public interface GoalActionUseCases {

    interface AddGoalActionUseCase {
        /** Acrescenta uma ação (no máximo 10: {@code GOAL_ACTION_LIMIT}). */
        GoalDetails handle(AddGoalActionCommand command);

        record AddGoalActionCommand(UUID userId, UUID goalId, String title, GoalActionFrequency frequency) {
        }
    }

    interface UpdateGoalActionUseCase {
        /** Troca título e frequência; mudar a frequência apaga os check-ins (períodos de outro formato). */
        GoalDetails handle(UpdateGoalActionCommand command);

        record UpdateGoalActionCommand(UUID userId, UUID goalId, UUID actionId, String title, GoalActionFrequency frequency) {
        }
    }

    interface DeleteGoalActionUseCase {
        GoalDetails handle(DeleteGoalActionCommand command);

        record DeleteGoalActionCommand(UUID userId, UUID goalId, UUID actionId) {
        }
    }

    interface CheckInGoalActionUseCase {
        /** Marca (ou desmarca, {@code done=false}) o período; idempotente. Período fora do formato da frequência: {@code GOAL_CHECKIN_PERIOD_INVALID}. */
        GoalDetails handle(CheckInGoalActionCommand command);

        record CheckInGoalActionCommand(UUID userId, UUID goalId, UUID actionId, String period, boolean done) {
        }
    }
}
