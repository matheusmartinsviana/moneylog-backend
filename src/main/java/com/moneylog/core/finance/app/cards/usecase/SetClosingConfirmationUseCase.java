package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.CardDetails;

public interface SetClosingConfirmationUseCase {

    CardDetails handle(SetClosingConfirmationCommand command);

    record SetClosingConfirmationCommand(UUID userId, UUID cardId, boolean confirmed, long version) {
    }
}
