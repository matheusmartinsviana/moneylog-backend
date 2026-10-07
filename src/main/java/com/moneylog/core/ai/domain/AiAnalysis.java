package com.moneylog.core.ai.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Registro de cada análise (S-183, PR 00 §27). Guarda o que foi enviado ({@code redactedPayload}, apagado após a retenção), o hash, o uso de
 * tokens e o resultado. {@code DENIED} nunca carrega payload. Nunca guarda a chave, cabeçalhos nem a resposta bruta do provedor.
 */
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

    /** Explícito para o front e a documentação: nada de texto da IA quando o aterramento falhou. */
    public boolean hasResult() {
        return status == AiStatus.COMPLETED && resultText != null;
    }
}
