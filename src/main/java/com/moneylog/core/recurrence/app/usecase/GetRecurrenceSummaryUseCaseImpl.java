package com.moneylog.core.recurrence.app.usecase;

import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.domain.MonthlyEstimate;
import com.moneylog.core.sk.time.TimeProvider;

public class GetRecurrenceSummaryUseCaseImpl implements GetRecurrenceSummaryUseCase {

    private final RecurrenceGateway recurrences;
    private final TimeProvider time;

    public GetRecurrenceSummaryUseCaseImpl(RecurrenceGateway recurrences, TimeProvider time) {
        this.recurrences = recurrences;
        this.time = time;
    }

    @Override
    public MonthlyEstimate handle(GetRecurrenceSummaryQuery query) {
        return MonthlyEstimate.of(recurrences.findAll(query.userId()), time.today(), query.gross());
    }
}
