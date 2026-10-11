package com.moneylog.core.ai.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AiPreview(UUID id, UUID userId, AiPurpose purpose, String period, String language, String payload, String payloadHash, AiFacts facts,
                        Map<String, Integer> counts, List<String> redactions, int estimatedTokens, Instant createdAt, Instant expiresAt,
                        Instant usedAt) {

    public static final int TTL_MINUTES = 15;

    public AiPreview {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(payloadHash, "payloadHash");
        Objects.requireNonNull(facts, "facts");
        counts = Map.copyOf(counts);
        redactions = List.copyOf(redactions);
    }

    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean used() {
        return usedAt != null;
    }
}
