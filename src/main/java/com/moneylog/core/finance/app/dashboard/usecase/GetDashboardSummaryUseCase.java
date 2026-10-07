package com.moneylog.core.finance.app.dashboard.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.dashboard.DashboardView;

public interface GetDashboardSummaryUseCase {

    /**
     * Resumo do Início. Tudo sai de {@code DashboardSummary.of} sobre o {@code FinancialRecords} do carregamento único
     * (S-79): o mesmo que a lista e os lançamentos usam.
     */
    DashboardView handle(GetDashboardSummaryQuery query);

    /** @param asOf data do resumo; {@code null} = hoje no fuso do usuário (depois de hoje é projeção) */
    record GetDashboardSummaryQuery(UUID userId, LocalDate asOf) {
    }
}
