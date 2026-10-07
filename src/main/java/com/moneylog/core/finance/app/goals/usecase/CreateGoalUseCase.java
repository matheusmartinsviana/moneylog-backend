package com.moneylog.core.finance.app.goals.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.goal.domain.GoalPriority;
import com.moneylog.core.sk.money.Money;

public interface CreateGoalUseCase {

    /** Escopo da chave de idempotência, próprio desta rota. */
    String IDEMPOTENCY_SCOPE = "POST /v1/goals";

    /**
     * Cria a meta (sem reservas: {@code current} 0,00). Com {@code Idempotency-Key}, repetir o mesmo comando devolve a mesma meta
     * (estado atual); outro comando com a mesma chave é {@code IDEMPOTENCY_KEY_REUSED}.
     */
    GoalDetails handle(CreateGoalCommand command);

    record CreateGoalCommand(UUID userId, String name, Money target, LocalDate deadline, GoalPriority priority, String description,
                             UUID idempotencyKey) {
    }
}
