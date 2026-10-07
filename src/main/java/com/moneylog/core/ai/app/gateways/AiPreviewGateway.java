package com.moneylog.core.ai.app.gateways;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.ai.domain.AiPreview;

public interface AiPreviewGateway {

    AiPreview save(AiPreview preview);

    Optional<AiPreview> findById(UUID userId, UUID id);

    /** Reivindica a prévia de forma atômica (uso único): {@code false} se já foi usada. */
    boolean markUsed(UUID userId, UUID id, Instant at);

    /** Apaga as prévias expiradas antes de {@code before}. */
    int deleteExpiredBefore(Instant before);
}
