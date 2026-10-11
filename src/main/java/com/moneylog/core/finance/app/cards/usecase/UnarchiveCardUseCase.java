package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.CardDetails;

public interface UnarchiveCardUseCase {

    CardDetails handle(UnarchiveCardCommand command);

    record UnarchiveCardCommand(UUID userId, UUID cardId) {
    }
}
