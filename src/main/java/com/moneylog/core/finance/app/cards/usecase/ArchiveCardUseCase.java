package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.CardDetails;

public interface ArchiveCardUseCase {

    CardDetails handle(ArchiveCardCommand command);

    record ArchiveCardCommand(UUID userId, UUID cardId) {
    }
}
