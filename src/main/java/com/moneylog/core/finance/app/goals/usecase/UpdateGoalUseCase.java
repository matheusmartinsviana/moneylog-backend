package com.moneylog.core.finance.app.goals.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.goal.domain.GoalPriority;
import com.moneylog.core.sk.money.Money;

public interface UpdateGoalUseCase {

    /**
     * Edita os dados da meta (nome, alvo, prazo, prioridade, descrição); as reservas não mudam. {@code version} desatualizada →
     * {@code VERSION_CONFLICT}; meta arquivada → {@code GOAL_ARCHIVED}.
     */
    GoalDetails handle(UpdateGoalCommand command);

    /**
     * {@code name} e {@code target} só são exigidos em {@code SAVINGS}; em {@code METRIC} o nome é opcional (vazio apaga) e o alvo ausente
     * mantém o atual; em {@code DEBT_PAYOFF} ausentes mantêm os atuais. {@code planNote} vazio apaga o texto do plano.
     */
    record UpdateGoalCommand(UUID userId, UUID goalId, String name, Money target, LocalDate deadline, GoalPriority priority,
                             String description, long version, String planNote) {

        public UpdateGoalCommand(UUID userId, UUID goalId, String name, Money target, LocalDate deadline, GoalPriority priority,
                                 String description, long version) {
            this(userId, goalId, name, target, deadline, priority, description, version, null);
        }
    }
}
