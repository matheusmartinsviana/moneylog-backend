package com.moneylog.core.finance.app.reports.usecase;

import java.util.UUID;

import com.moneylog.core.finance.domain.IncomeExpenseSeries;
import com.moneylog.core.finance.domain.ReportPeriod;

public interface GetIncomeExpenseSeriesUseCase {

    /**
     * Série de entradas e saídas do período (S-283): uma só carga do razão ({@code FinancialRecordsGateway.load}) e só
     * {@link IncomeExpenseSeries#of}. Leitura pura: não altera nada e não toma trava.
     */
    IncomeExpenseSeries handle(GetIncomeExpenseSeriesQuery query);

    record GetIncomeExpenseSeriesQuery(UUID userId, ReportPeriod period) {
    }
}
