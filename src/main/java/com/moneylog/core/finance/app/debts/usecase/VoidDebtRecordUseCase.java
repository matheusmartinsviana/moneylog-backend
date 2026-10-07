package com.moneylog.core.finance.app.debts.usecase;

import java.util.UUID;

import com.moneylog.core.debt.app.DebtView;

public interface VoidDebtRecordUseCase {

    /**
     * Estorna o <b>registro</b> de uma dívida sem movimentação (D1 §2.6): só sem quitações ativas e sem encerramento; os
     * lançamentos dela ficam inativos e a dívida vira {@code VOIDED}. Com quitação → {@code DEBT_HAS_SETTLEMENTS}; dívida que nasceu
     * de uma movimentação → {@code DEBT_HAS_ORIGIN_TRANSACTION} (estorne a movimentação de origem).
     */
    DebtView handle(VoidDebtRecordCommand command);

    record VoidDebtRecordCommand(UUID userId, UUID debtId, String reason) {
    }
}
