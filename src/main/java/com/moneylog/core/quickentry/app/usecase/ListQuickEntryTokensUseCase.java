package com.moneylog.core.quickentry.app.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.quickentry.app.QuickEntryTokenView;

public interface ListQuickEntryTokensUseCase {

    List<QuickEntryTokenView> handle(ListQuickEntryTokensQuery query);

    record ListQuickEntryTokensQuery(UUID userId) {
    }
}
