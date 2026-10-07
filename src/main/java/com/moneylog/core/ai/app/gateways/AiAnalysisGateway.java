package com.moneylog.core.ai.app.gateways;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.ai.domain.AiAnalysis;
import com.moneylog.core.ai.domain.AiPurpose;
import com.moneylog.core.ai.domain.AiStatus;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

/** Todo método filtra pelo {@code userId}. */
public interface AiAnalysisGateway {

    AiAnalysis save(AiAnalysis analysis);

    Optional<AiAnalysis> findById(UUID userId, UUID id);

    Optional<AiAnalysis> findByIdempotencyKey(UUID userId, UUID key);

    /** Mais recentes primeiro; filtros nulos = todos. */
    Slice<AiAnalysis> list(UUID userId, AiPurpose purpose, AiStatus status, PageQuery page);

    /** Chamadas ao provedor (COMPLETED, FAILED, REJECTED_UNGROUNDED) no intervalo {@code [from, to)}. */
    long countProviderCalls(UUID userId, Instant from, Instant to);

    long sumTokens(UUID userId, Instant from, Instant to);

    /** Apaga só o payload enviado das análises anteriores a {@code before}; o registro permanece. Devolve quantas. */
    int purgePayloadsBefore(Instant before, Instant purgedAt);
}
