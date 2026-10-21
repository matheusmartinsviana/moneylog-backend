package com.moneylog.infra.quickentry.controller;

import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.quickentry.app.IssuedQuickEntryToken;
import com.moneylog.core.quickentry.app.QuickEntryTokenView;
import com.moneylog.core.quickentry.domain.QuickEntryScope;
import com.moneylog.core.quickentry.domain.QuickEntryToken;
import com.moneylog.core.quickentry.domain.QuickEntryTokenStatus;
import com.moneylog.core.quickentry.domain.TokenRevocationReason;

public record QuickEntryTokenCreatedResponse(UUID id, String label, String prefix, QuickEntryScope scope, UUID accountId,
                                             String accountName, QuickEntryTokenStatus status, Instant expiresAt, Instant createdAt,
                                             Instant lastUsedAt, String lastUsedIp, String lastUsedUserAgent, Instant revokedAt,
                                             TokenRevocationReason revokedReason, String token) {

    public static QuickEntryTokenCreatedResponse of(IssuedQuickEntryToken issued) {
        QuickEntryTokenView view = issued.view();
        QuickEntryToken t = view.token();
        return new QuickEntryTokenCreatedResponse(t.id(), t.label(), t.prefix(), t.scope(), t.accountId(), view.accountName(), view.status(),
                t.expiresAt(), t.createdAt(), t.lastUsedAt(), t.lastUsedIp(), t.lastUsedUserAgent(), t.revokedAt(), t.revokedReason(),
                issued.secret());
    }

    @Override
    public String toString() {
        return "QuickEntryTokenCreatedResponse[id=" + id + ", prefix=" + prefix + "]";
    }
}
