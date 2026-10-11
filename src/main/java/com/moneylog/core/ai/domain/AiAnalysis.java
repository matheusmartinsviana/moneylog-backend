package com.moneylog.core.ai.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AiAnalysis(UUID id, UUID userId, UUID previewId, UUID idempotencyKey, AiPurpose purpose, String period, String provider, String model,
                         AiStatus status, String redactedPayload, String payloadHash, int inputTokens, int outputTokens, long durationMs,
                         String resultText, String caveat, List<String> factRefs, String failureReason, Instant createdAt, Instant payloadPurgedAt) {

    public AiAnalysis {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(status, "status");
        factRefs = factRefs == null ? List.of() : List.copyOf(factRefs);
        if (status == AiStatus.DENIED && redactedPayload != null) {
            throw new IllegalArgumentException("análise negada não guarda payload enviado");
        }
    }

    public int totalTokens() {
        return inputTokens + outputTokens;
    }

    public boolean hasResult() {
        return status == AiStatus.COMPLETED && resultText != null;
    }
}
