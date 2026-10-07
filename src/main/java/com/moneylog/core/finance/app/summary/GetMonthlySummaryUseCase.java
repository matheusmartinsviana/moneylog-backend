package com.moneylog.core.finance.app.summary;

import java.util.UUID;

import com.moneylog.core.sk.time.Competence;

public interface GetMonthlySummaryUseCase {

    /**
     * Resumo do mês (S-151): {@code MonthlySummary.of} sobre o carregamento único dos registros (S-79), o mesmo caminho do dashboard e
     * da lista por competência. Mês corrente: {@code partial}; mês futuro: {@code partial} e {@code isProjection}.
     */
    MonthlySummaryView handle(GetMonthlySummaryQuery query);

    record GetMonthlySummaryQuery(UUID userId, Competence month) {
    }
}
