package com.moneylog.core.finance.app.summary;

import java.util.UUID;

import com.moneylog.core.sk.time.Competence;

public interface GetMonthlySummaryUseCase {

    MonthlySummaryView handle(GetMonthlySummaryQuery query);

    record GetMonthlySummaryQuery(UUID userId, Competence month) {
    }
}
