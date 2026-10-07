package com.moneylog.core.recurrence.app.usecase;

import com.moneylog.core.recurrence.app.RecurrenceView;
import com.moneylog.core.recurrence.app.RecurrenceViews;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceNotFoundException;
import com.moneylog.core.sk.time.TimeProvider;

public class GetRecurrenceUseCaseImpl implements GetRecurrenceUseCase {

    private final RecurrenceGateway recurrences;
    private final RecurrenceViews views;
    private final TimeProvider time;

    public GetRecurrenceUseCaseImpl(RecurrenceGateway recurrences, RecurrenceViews views, TimeProvider time) {
        this.recurrences = recurrences;
        this.views = views;
        this.time = time;
    }

    @Override
    public RecurrenceView handle(GetRecurrenceQuery query) {
        return views.one(query.userId(), recurrences.findVersioned(query.userId(), query.recurrenceId())
                .orElseThrow(() -> new RecurrenceNotFoundException(query.recurrenceId())), time.today());
    }
}
