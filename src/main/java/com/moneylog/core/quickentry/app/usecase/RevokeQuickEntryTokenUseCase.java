package com.moneylog.core.quickentry.app.usecase;

import java.util.UUID;

import com.moneylog.core.quickentry.app.QuickEntryTokenView;

public interface RevokeQuickEntryTokenUseCase {

    Revoked handle(RevokeQuickEntryTokenCommand command);

    record RevokeQuickEntryTokenCommand(UUID userId, UUID tokenId) {
    }

    record Revoked(QuickEntryTokenView view, boolean changed) {
    }
}
