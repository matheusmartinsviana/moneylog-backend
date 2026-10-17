package com.moneylog.core.recurrence.app.usecase;

import java.util.UUID;

import com.moneylog.core.recurrence.domain.MonthlyEstimate;

public interface GetRecurrenceSummaryUseCase {

    MonthlyEstimate handle(GetRecurrenceSummaryQuery query);

    record GetRecurrenceSummaryQuery(UUID userId, boolean gross) {
    }
}
