package com.moneylog.core.finance.app.dashboard.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.dashboard.DashboardView;

public interface GetDashboardSummaryUseCase {

    DashboardView handle(GetDashboardSummaryQuery query);

    record GetDashboardSummaryQuery(UUID userId, LocalDate asOf) {
    }
}
