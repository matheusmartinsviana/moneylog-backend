package com.moneylog.core.finance.app.reports.usecase;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.IncomeExpenseSeries;
import com.moneylog.core.sk.time.TimeProvider;

/** Mesma fonte do dashboard (S-79): o {@link FinancialRecords} carregado uma vez; "hoje" é o do relógio injetado (fuso do usuário). */
public class GetIncomeExpenseSeriesUseCaseImpl implements GetIncomeExpenseSeriesUseCase {

    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public GetIncomeExpenseSeriesUseCaseImpl(FinancialRecordsGateway records, TimeProvider time) {
        this.records = records;
        this.time = time;
    }

    @Override
    public IncomeExpenseSeries handle(GetIncomeExpenseSeriesQuery query) {
        FinancialRecords loaded = records.load(query.userId());
        return IncomeExpenseSeries.of(loaded.ledger(), query.period(), time.today());
    }
}
