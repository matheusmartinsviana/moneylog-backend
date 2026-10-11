package com.moneylog.core.finance.app.goals.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.goal.domain.GoalActionFrequency;

public interface GoalActionUseCases {

    interface AddGoalActionUseCase {
        GoalDetails handle(AddGoalActionCommand command);

        record AddGoalActionCommand(UUID userId, UUID goalId, String title, GoalActionFrequency frequency) {
        }
    }

    interface UpdateGoalActionUseCase {
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
        GoalDetails handle(CheckInGoalActionCommand command);

        record CheckInGoalActionCommand(UUID userId, UUID goalId, UUID actionId, String period, boolean done) {
        }
    }
}
