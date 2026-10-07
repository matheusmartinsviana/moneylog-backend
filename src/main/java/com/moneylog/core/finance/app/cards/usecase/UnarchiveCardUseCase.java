package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.CardDetails;

public interface UnarchiveCardUseCase {

    /** Reativa o cartão arquivado; um cartão que não está arquivado responde {@code CARD_NOT_ARCHIVED}. */
    CardDetails handle(UnarchiveCardCommand command);

    record UnarchiveCardCommand(UUID userId, UUID cardId) {
    }
}
