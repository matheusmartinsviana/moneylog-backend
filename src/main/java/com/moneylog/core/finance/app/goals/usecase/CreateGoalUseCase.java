package com.moneylog.core.finance.app.goals.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.goal.domain.GoalKind;
import com.moneylog.core.goal.domain.GoalMetric;
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

    /**
     * @param kind   {@code null} = {@code SAVINGS} (clientes antigos não mudam). Em {@code DEBT_PAYOFF} o {@code target} é ignorado
     *               (sem alvo; a baseline vem de "cadastrei tudo") e o nome padrão é "Quitar todas as dívidas"; só uma ativa por usuário
     *               ({@code DEBT_PAYOFF_GOAL_EXISTS}). Em {@code METRIC} (ADR-016) {@code metric} e {@code target} são obrigatórios, o nome
     *               é opcional e a baseline é o valor atual da medida, capturado na criação
     */
    record CreateGoalCommand(UUID userId, String name, Money target, LocalDate deadline, GoalPriority priority, String description,
                             UUID idempotencyKey, GoalKind kind, GoalMetric metric) {

        /** Meta de guardar dinheiro ({@code SAVINGS}). */
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
