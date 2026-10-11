package com.moneylog.core.finance.app.debts.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.debt.app.DebtView;

public interface UpdateDebtUseCase {

    DebtView handle(UpdateDebtCommand command);

    record UpdateDebtCommand(UUID userId, UUID debtId, LocalDate expectedDate, String description, long version) {
    }
}
