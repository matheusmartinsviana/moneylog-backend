package com.moneylog.core.recurrence.app.usecase;

import com.moneylog.core.recurrence.app.RecurrenceView;
import com.moneylog.core.recurrence.app.RecurrenceViews;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class ListRecurrencesUseCaseImpl implements ListRecurrencesUseCase {

    private final RecurrenceGateway recurrences;
    private final RecurrenceViews views;
    private final TimeProvider time;

    public ListRecurrencesUseCaseImpl(RecurrenceGateway recurrences, RecurrenceViews views, TimeProvider time) {
        this.recurrences = recurrences;
        this.views = views;
        this.time = time;
    }

    @Override
    public Slice<RecurrenceView> handle(ListRecurrencesQuery query) {
        Slice<Versioned<RecurringTransaction>> page = recurrences.list(query.userId(), query.active(), query.page());
        return new Slice<>(views.of(query.userId(), page.items(), time.today()), page.hasNext());
    }
}
