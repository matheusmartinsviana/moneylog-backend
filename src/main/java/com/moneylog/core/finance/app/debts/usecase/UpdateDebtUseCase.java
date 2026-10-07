package com.moneylog.core.finance.app.debts.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.debt.app.DebtView;

public interface UpdateDebtUseCase {

    /**
     * Edita a previsão e a descrição (o principal, a pessoa e a origem não mudam); o compromisso muda de data. Dívida encerrada
     * → {@code DEBT_CLOSED}; {@code version} desatualizada → {@code VersionConflictException} (409).
     */
    DebtView handle(UpdateDebtCommand command);

    record UpdateDebtCommand(UUID userId, UUID debtId, LocalDate expectedDate, String description, long version) {
    }
}
