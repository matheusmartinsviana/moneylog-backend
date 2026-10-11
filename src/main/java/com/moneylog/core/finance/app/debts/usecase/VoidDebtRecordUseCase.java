package com.moneylog.core.finance.app.debts.usecase;

import java.util.UUID;

import com.moneylog.core.debt.app.DebtView;

public interface VoidDebtRecordUseCase {

    DebtView handle(VoidDebtRecordCommand command);

    record VoidDebtRecordCommand(UUID userId, UUID debtId, String reason) {
    }
}
