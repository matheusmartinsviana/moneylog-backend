package com.moneylog.core.finance.app.debts.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.debts.DebtOverviewView;

public interface GetDebtOverviewUseCase {

    DebtOverviewView handle(GetDebtOverviewQuery query);

    record GetDebtOverviewQuery(UUID userId, int months) {
    }
}
