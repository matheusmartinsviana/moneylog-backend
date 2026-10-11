package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.FutureInstallmentsView;

public interface GetFutureInstallmentsUseCase {

    FutureInstallmentsView handle(GetFutureInstallmentsQuery query);

    record GetFutureInstallmentsQuery(UUID userId, UUID cardId) {
    }
}
