package com.moneylog.core.finance.app.recurrences.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.recurrence.app.OccurrenceView;

public interface SkipOccurrenceUseCase {

    OccurrenceView handle(SkipOccurrenceCommand command);

    record SkipOccurrenceCommand(UUID userId, UUID recurrenceId, LocalDate occurrenceDate) {
    }
}
