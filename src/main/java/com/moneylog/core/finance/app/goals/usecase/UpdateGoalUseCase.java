package com.moneylog.core.finance.app.goals.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.goal.domain.GoalPriority;
import com.moneylog.core.sk.money.Money;

public interface UpdateGoalUseCase {

    GoalDetails handle(UpdateGoalCommand command);

    record UpdateGoalCommand(UUID userId, UUID goalId, String name, Money target, LocalDate deadline, GoalPriority priority,
                             String description, long version, String planNote) {

        public UpdateGoalCommand(UUID userId, UUID goalId, String name, Money target, LocalDate deadline, GoalPriority priority,
                                 String description, long version) {
            this(userId, goalId, name, target, deadline, priority, description, version, null);
        }
    }
}
