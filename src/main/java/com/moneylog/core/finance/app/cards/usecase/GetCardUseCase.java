package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.CardDetails;

public interface GetCardUseCase {

    CardDetails handle(GetCardQuery query);

    record GetCardQuery(UUID userId, UUID cardId) {
    }
}
