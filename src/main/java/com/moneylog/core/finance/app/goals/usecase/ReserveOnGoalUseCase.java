package com.moneylog.core.finance.app.goals.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.sk.money.Money;

public interface ReserveOnGoalUseCase {

    String IDEMPOTENCY_SCOPE = "POST /v1/goals/{id}/reservations";

    GoalDetails handle(ReserveOnGoalCommand command);

    record ReserveOnGoalCommand(UUID userId, UUID goalId, UUID accountId, Money amount, LocalDate date, String note, UUID idempotencyKey) {
    }
}
