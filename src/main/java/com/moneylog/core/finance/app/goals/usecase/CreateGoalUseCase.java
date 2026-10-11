package com.moneylog.core.finance.app.goals.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.goal.domain.GoalKind;
import com.moneylog.core.goal.domain.GoalMetric;
import com.moneylog.core.goal.domain.GoalPriority;
import com.moneylog.core.sk.money.Money;

public interface CreateGoalUseCase {

    String IDEMPOTENCY_SCOPE = "POST /v1/goals";

    GoalDetails handle(CreateGoalCommand command);

    record CreateGoalCommand(UUID userId, String name, Money target, LocalDate deadline, GoalPriority priority, String description,
                             UUID idempotencyKey, GoalKind kind, GoalMetric metric) {

        public CreateGoalCommand(UUID userId, String name, Money target, LocalDate deadline, GoalPriority priority, String description,
                                 UUID idempotencyKey) {
            this(userId, name, target, deadline, priority, description, idempotencyKey, null, null);
        }

        public CreateGoalCommand(UUID userId, String name, Money target, LocalDate deadline, GoalPriority priority, String description,
                                 UUID idempotencyKey, GoalKind kind) {
            this(userId, name, target, deadline, priority, description, idempotencyKey, kind, null);
        }
    }
}
