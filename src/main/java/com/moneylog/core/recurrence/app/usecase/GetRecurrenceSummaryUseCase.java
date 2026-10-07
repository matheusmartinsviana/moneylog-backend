package com.moneylog.core.recurrence.app.usecase;

import java.util.UUID;

import com.moneylog.core.recurrence.domain.MonthlyEstimate;

public interface GetRecurrenceSummaryUseCase {

    /** Custo fixo mensal e receita recorrente mensal (Σ das ativas normalizadas para o mês): sempre estimativa (S-146). */
    MonthlyEstimate handle(GetRecurrenceSummaryQuery query);

    record GetRecurrenceSummaryQuery(UUID userId) {
    }
}
