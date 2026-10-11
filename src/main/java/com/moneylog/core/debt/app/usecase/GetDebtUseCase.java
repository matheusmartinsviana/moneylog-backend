package com.moneylog.core.debt.app.usecase;

import java.util.UUID;

import com.moneylog.core.debt.app.DebtView;

public interface GetDebtUseCase {

    DebtView handle(GetDebtQuery query);

    record GetDebtQuery(UUID userId, UUID debtId) {
    }
}
