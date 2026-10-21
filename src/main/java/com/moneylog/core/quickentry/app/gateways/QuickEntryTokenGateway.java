package com.moneylog.core.quickentry.app.gateways;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.quickentry.domain.QuickEntryToken;
import com.moneylog.core.quickentry.domain.TokenRevocationReason;

public interface QuickEntryTokenGateway {

    void insert(QuickEntryToken token);

    void update(QuickEntryToken token);

    Optional<QuickEntryToken> findById(UUID userId, UUID id);

    Optional<QuickEntryToken> findByTokenHash(UUID userId, String tokenHash);

    Optional<QuickEntryToken> findByTokenHashForAuthentication(String tokenHash);

    List<QuickEntryToken> listNewestFirst(UUID userId);

    long countActive(UUID userId, Instant now);

    int revokeAllActive(UUID userId, TokenRevocationReason reason, Instant now);

    boolean recordUse(UUID userId, UUID id, Instant at, Instant usedBefore, String maskedIp, String userAgent);
}
