package com.moneylog.core.finance.app.dashboard.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.dashboard.DashboardView;
import com.moneylog.core.finance.domain.ConsumptionView;

public interface GetDashboardSummaryUseCase {

    DashboardView handle(GetDashboardSummaryQuery query);

    record GetDashboardSummaryQuery(UUID userId, LocalDate asOf, ConsumptionView view) {

        public GetDashboardSummaryQuery {
            view = ConsumptionView.orDefault(view);
        }

        public GetDashboardSummaryQuery(UUID userId, LocalDate asOf) {
            this(userId, asOf, null);
        }
    }
}
