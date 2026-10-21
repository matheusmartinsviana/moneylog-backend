package com.moneylog.core.quickentry.app.usecase;

import java.util.UUID;

import com.moneylog.core.quickentry.app.IssuedQuickEntryToken;

public interface CreateQuickEntryTokenUseCase {

    IssuedQuickEntryToken handle(CreateQuickEntryTokenCommand command);

    record CreateQuickEntryTokenCommand(UUID userId, String label, UUID accountId, Integer expiresInDays) {
    }
}
