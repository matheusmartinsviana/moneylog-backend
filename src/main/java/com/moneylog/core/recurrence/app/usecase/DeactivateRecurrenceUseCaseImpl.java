package com.moneylog.core.recurrence.app.usecase;

import com.moneylog.core.recurrence.app.RecurrenceView;
import com.moneylog.core.recurrence.app.RecurrenceViews;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceNotFoundException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class DeactivateRecurrenceUseCaseImpl implements DeactivateRecurrenceUseCase {

    private final RecurrenceGateway recurrences;
    private final RecurrenceViews views;
    private final TimeProvider time;

    public DeactivateRecurrenceUseCaseImpl(RecurrenceGateway recurrences, RecurrenceViews views, TimeProvider time) {
        this.recurrences = recurrences;
        this.views = views;
        this.time = time;
    }

    @Override
    public RecurrenceView handle(DeactivateRecurrenceCommand command) {
        recurrences.lockForUpdate(command.userId(), command.recurrenceId());
        Versioned<RecurringTransaction> current = recurrences.findVersioned(command.userId(), command.recurrenceId())
                .orElseThrow(() -> new RecurrenceNotFoundException(command.recurrenceId()));
        RecurringTransaction recurrence = current.value();
        if (!recurrence.isActive()) {
            return views.one(command.userId(), current, time.today());
        }
        recurrence.deactivate();
        return views.one(command.userId(), recurrences.save(recurrence), time.today());
    }
}
