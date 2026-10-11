package com.moneylog.core.finance.app.dashboard.usecase;

import java.time.LocalDate;

import com.moneylog.core.finance.app.dashboard.DashboardView;
import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.DashboardSummary;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.time.TimeProvider;

public class GetDashboardSummaryUseCaseImpl implements GetDashboardSummaryUseCase {

    private final FinancialRecordsGateway records;
    private final CommitmentSourcesGateway sources;
    private final TimeProvider time;

    public GetDashboardSummaryUseCaseImpl(FinancialRecordsGateway records, CommitmentSourcesGateway sources, TimeProvider time) {
        this.records = records;
        this.sources = sources;
        this.time = time;
    }

    @Override
    public DashboardView handle(GetDashboardSummaryQuery query) {
        FinancialRecords loaded = records.load(query.userId());
        LocalDate today = time.today();
        LocalDate asOf = query.asOf() == null ? today : query.asOf();
        DashboardSummary summary = DashboardSummary.of(loaded, sources.recurrences(query.userId()), asOf, today);
        return new DashboardView(summary, !loaded.accounts().isEmpty());
    }
}
