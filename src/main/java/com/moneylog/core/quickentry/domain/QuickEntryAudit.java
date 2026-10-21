package com.moneylog.core.quickentry.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record QuickEntryAudit(UUID transactionId, UUID tokenId, UUID userId, String ip, Instant createdAt) {

    public QuickEntryAudit {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(createdAt, "createdAt");
        ip = IpMask.mask(ip);
    }
}
