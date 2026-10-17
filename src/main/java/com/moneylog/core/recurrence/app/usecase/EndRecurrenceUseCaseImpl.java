package com.moneylog.core.recurrence.app.usecase;

import java.time.LocalDate;

import com.moneylog.core.recurrence.app.RecurrenceView;
import com.moneylog.core.recurrence.app.RecurrenceViews;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.InvalidRecurrenceException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceNotFoundException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.FinancialDates;
import com.moneylog.core.sk.time.TimeProvider;

public class EndRecurrenceUseCaseImpl implements EndRecurrenceUseCase {

    private final RecurrenceGateway recurrences;
    private final RecurrenceViews views;
    private final TimeProvider time;

    public EndRecurrenceUseCaseImpl(RecurrenceGateway recurrences, RecurrenceViews views, TimeProvider time) {
        this.recurrences = recurrences;
        this.views = views;
        this.time = time;
    }

    @Override
    public RecurrenceView handle(EndRecurrenceCommand command) {
        recurrences.lockForUpdate(command.userId(), command.recurrenceId());
        Versioned<RecurringTransaction> current = recurrences.findVersioned(command.userId(), command.recurrenceId())
                .orElseThrow(() -> new RecurrenceNotFoundException(command.recurrenceId()));
        RecurringTransaction recurrence = current.value();
        if (command.endDate() != null && command.activeUntil() != null && !command.endDate().equals(command.activeUntil())) {
            throw new InvalidRecurrenceException("endDate e activeUntil são sinônimos e precisam coincidir.");
        }
        LocalDate requested = command.activeUntil() != null ? command.activeUntil() : command.endDate();
        LocalDate end = FinancialDates.requireInWindow(requested == null ? time.today() : requested, "endDate");
        recurrence.endOn(end);
        return views.one(command.userId(), recurrences.save(recurrence), time.today());
    }
}
