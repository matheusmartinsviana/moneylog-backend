package com.moneylog.core.goal.app.gateways;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.goal.domain.GoalSnapshot;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

/** Instantâneos mensais da medida das metas (histórico de consistência, ADR-016). Todo método filtra pelo {@code userId}. */
public interface GoalSnapshotGateway {

    /** Instantâneos das metas, numa única consulta, agrupados por meta. */
    Map<UUID, List<GoalSnapshot>> findByGoals(UUID userId, Collection<UUID> goalIds);

    /** Grava ou atualiza o instantâneo do mês (idempotente: um por meta e mês). Participa da transação do chamador. */
    void upsert(UUID userId, UUID goalId, Competence month, Money value, Instant capturedAt);

    /**
     * Grava o instantâneo do mês só se ainda não existir, em transação <b>própria</b> (usado na leitura, que é somente leitura).
     * Nunca falha o chamador: erro é registrado e engolido.
     */
    void touchIfAbsent(UUID userId, UUID goalId, Competence month, Money value, Instant capturedAt);

    /** Usuários com meta de medida ou de quitar dívidas ativa (para o job diário). */
    List<UUID> userIdsWithTrackedGoals();
}
