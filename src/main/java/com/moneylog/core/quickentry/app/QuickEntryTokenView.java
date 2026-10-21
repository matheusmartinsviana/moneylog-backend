package com.moneylog.core.quickentry.app;

import java.time.Instant;

import com.moneylog.core.quickentry.domain.QuickEntryToken;
import com.moneylog.core.quickentry.domain.QuickEntryTokenStatus;

public record QuickEntryTokenView(QuickEntryToken token, String accountName, QuickEntryTokenStatus status) {

    public static QuickEntryTokenView of(QuickEntryToken token, String accountName, Instant now) {
        return new QuickEntryTokenView(token, accountName, token.statusAt(now));
    }
}
