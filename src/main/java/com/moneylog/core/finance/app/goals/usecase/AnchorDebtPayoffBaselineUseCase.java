package com.moneylog.core.finance.app.goals.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;

public interface AnchorDebtPayoffBaselineUseCase {

    GoalDetails handle(AnchorDebtPayoffBaselineCommand command);

    record AnchorDebtPayoffBaselineCommand(UUID userId, UUID goalId) {
    }
}
