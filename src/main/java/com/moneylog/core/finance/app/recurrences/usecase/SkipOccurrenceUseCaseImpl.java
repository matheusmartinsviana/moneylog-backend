package com.moneylog.core.finance.app.recurrences.usecase;

import com.moneylog.core.recurrence.app.OccurrenceView;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceNotFoundException;

public class SkipOccurrenceUseCaseImpl implements SkipOccurrenceUseCase {

    private final RecurrenceGateway recurrences;

    public SkipOccurrenceUseCaseImpl(RecurrenceGateway recurrences) {
        this.recurrences = recurrences;
    }

    @Override
    public OccurrenceView handle(SkipOccurrenceCommand command) {
        // pular e realizar a mesma ocorrência se serializam na recorrência; a realizada é lida sob a trava
        recurrences.lockForUpdate(command.userId(), command.recurrenceId());
        RecurringTransaction recurrence = recurrences.findById(command.userId(), command.recurrenceId())
                .orElseThrow(() -> new RecurrenceNotFoundException(command.recurrenceId()));
        recurrence.skip(command.occurrenceDate());
        recurrences.save(recurrence);
        return OccurrenceView.skipped(recurrence, command.occurrenceDate());
    }
}
