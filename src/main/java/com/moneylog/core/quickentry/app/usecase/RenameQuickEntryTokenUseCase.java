package com.moneylog.core.quickentry.app.usecase;

import java.util.UUID;

import com.moneylog.core.quickentry.app.QuickEntryTokenView;

public interface RenameQuickEntryTokenUseCase {

    QuickEntryTokenView handle(RenameQuickEntryTokenCommand command);

    record RenameQuickEntryTokenCommand(UUID userId, UUID tokenId, String label) {
    }
}
