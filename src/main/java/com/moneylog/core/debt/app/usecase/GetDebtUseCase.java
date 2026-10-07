package com.moneylog.core.debt.app.usecase;

import java.util.UUID;

import com.moneylog.core.debt.app.DebtView;

public interface GetDebtUseCase {

    /** A dívida com as quitações e as ações que o estado permite. Dívida de outro usuário → {@code DEBT_NOT_FOUND}. */
    DebtView handle(GetDebtQuery query);

    record GetDebtQuery(UUID userId, UUID debtId) {
    }
}
