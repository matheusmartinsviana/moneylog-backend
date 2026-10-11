package com.moneylog.core.insights.app.gateways;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.insights.domain.Dismissal;

/**
 * Auditoria das dispensas, <b>só inserção</b>: não existe apagar nem editar além de gravar {@code revoked_at}. Todo método filtra pelo
 * {@code userId}; a linha de outro usuário não existe.
 */
public interface InsightDismissalGateway {

    /** Dispensas do usuário ainda não revogadas (uma consulta por requisição). */
    List<Dismissal> findActive(UUID userId);

    void insert(Dismissal dismissal);

    /** Marca como revogadas (uma vez) as dispensas ativas com estes ids; devolve quantas mudaram. */
    int revoke(UUID userId, Collection<UUID> ids, Instant at);
}
