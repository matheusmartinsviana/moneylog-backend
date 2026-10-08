package com.moneylog.core.finance.app.goals.usecase;

import java.util.UUID;

public interface RecordGoalSnapshotsUseCase {

    /**
     * Job diário (ADR-016): grava o instantâneo do mês corrente da medida de cada meta rastreada do usuário ({@code METRIC} e quitar
     * dívidas com baseline, não arquivadas). Idempotente: um registro por meta e mês, atualizado até o mês fechar. Devolve quantas metas
     * foram registradas.
     */
    int handle(UUID userId);
}
