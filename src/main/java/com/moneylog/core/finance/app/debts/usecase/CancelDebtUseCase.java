package com.moneylog.core.finance.app.debts.usecase;

import java.util.UUID;

import com.moneylog.core.debt.app.DebtView;

public interface CancelDebtUseCase {

    DebtView handle(CancelDebtCommand command);

    record CancelDebtCommand(UUID userId, UUID debtId, String reason) {
    }
}
