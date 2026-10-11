package com.moneylog.core.finance.app.projection;

import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.Projection;
import com.moneylog.core.sk.time.TimeProvider;

public class GetProjectionUseCaseImpl implements GetProjectionUseCase {

    private final FinancialRecordsGateway records;
    private final CommitmentSourcesGateway sources;
    private final TimeProvider time;

    public GetProjectionUseCaseImpl(FinancialRecordsGateway records, CommitmentSourcesGateway sources, TimeProvider time) {
        this.records = records;
        this.sources = sources;
        this.time = time;
    }

    @Override
    public Projection handle(GetProjectionQuery query) {
        FinancialRecords loaded = records.load(query.userId());
        return Projection.of(loaded, sources.recurrences(query.userId()), query.horizon(), time.today(), query.includeTrend());
    }
}
