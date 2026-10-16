package com.moneylog.core.finance.app.reports.usecase;

import java.util.UUID;

import com.moneylog.core.finance.domain.ConsumptionView;
import com.moneylog.core.finance.domain.IncomeExpenseSeries;
import com.moneylog.core.finance.domain.ReportPeriod;

public interface GetIncomeExpenseSeriesUseCase {

    IncomeExpenseSeries handle(GetIncomeExpenseSeriesQuery query);

    record GetIncomeExpenseSeriesQuery(UUID userId, ReportPeriod period, ConsumptionView view) {

        public GetIncomeExpenseSeriesQuery {
            view = ConsumptionView.orDefault(view);
        }

        public GetIncomeExpenseSeriesQuery(UUID userId, ReportPeriod period) {
            this(userId, period, null);
        }
    }
}
