package com.moneylog.infra.quickentry.gateways;

import com.moneylog.core.quickentry.domain.QuickEntryToken;
import com.moneylog.infra.quickentry.persistence.QuickEntryTokenEntity;

final class QuickEntryTokenRepositoryMapper {

    private QuickEntryTokenRepositoryMapper() {
    }

    static QuickEntryToken toDomain(QuickEntryTokenEntity e) {
        return new QuickEntryToken(e.getId(), e.getUserId(), e.getAccountId(), e.getLabel(), e.getPrefix(), e.getTokenHash(), e.getScope(),
                e.getExpiresAt(), e.getRevokedAt(), e.getRevokedReason(), e.getLastUsedAt(), e.getLastUsedIp(), e.getLastUsedUserAgent(),
                e.getCreatedAt(), e.getVersion() == null ? 0L : e.getVersion());
    }

    static QuickEntryTokenEntity toEntity(QuickEntryToken t) {
        QuickEntryTokenEntity entity = new QuickEntryTokenEntity(t.id(), t.userId(), t.accountId(), t.label(), t.prefix(), t.tokenHash(),
                t.scope(), t.expiresAt(), t.createdAt());
        entity.apply(t.label(), t.revokedAt(), t.revokedReason(), t.lastUsedAt(), t.lastUsedIp(), t.lastUsedUserAgent(), t.createdAt());
        return entity;
    }
}
