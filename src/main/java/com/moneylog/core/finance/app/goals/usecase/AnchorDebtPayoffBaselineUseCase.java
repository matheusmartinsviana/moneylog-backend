package com.moneylog.core.finance.app.goals.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;

public interface AnchorDebtPayoffBaselineUseCase {

    /**
     * "Cadastrei tudo" (ADR-013): grava {@code baseline = total de dívidas atual} e {@code baselineAt}. Pode ser repetido (re-ancorar, P8)
     * e é sempre ação explícita do usuário. Devolve a meta atualizada.
     *
     * @throws com.moneylog.core.goal.exception.GoalExceptions.DebtPayoffNothingToBaselineException total zero
     * @throws com.moneylog.core.goal.exception.GoalExceptions.GoalKindMismatchException            meta de guardar dinheiro
     */
    GoalDetails handle(AnchorDebtPayoffBaselineCommand command);

    record AnchorDebtPayoffBaselineCommand(UUID userId, UUID goalId) {
    }
}
