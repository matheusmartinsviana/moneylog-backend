package com.moneylog.core.finance.app.debts.usecase;

import java.util.UUID;

import com.moneylog.core.debt.app.DebtView;

public interface CancelDebtUseCase {

    /**
     * Perdão (S-19, D1 §2.6): só {@code OPEN}/{@code PARTIALLY_SETTLED}; zera o saldo restante contra {@code EQUITY}, sem despesa
     * nem receita. Não se desfaz (S-41). Sem motivo → {@code DEBT_REASON_REQUIRED}; {@code SETTLED} →
     * {@code DEBT_ALREADY_SETTLED}; já encerrada → {@code DEBT_CLOSED}.
     */
    DebtView handle(CancelDebtCommand command);

    record CancelDebtCommand(UUID userId, UUID debtId, String reason) {
    }
}
