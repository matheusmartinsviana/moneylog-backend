package com.moneylog.core.ai.app.gateways;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.ai.domain.AiPreview;

public interface AiPreviewGateway {

    AiPreview save(AiPreview preview);

    Optional<AiPreview> findById(UUID userId, UUID id);

    boolean markUsed(UUID userId, UUID id, Instant at);

    int deleteExpiredBefore(Instant before);
}
