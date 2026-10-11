package com.moneylog.core.recurrence.app.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.recurrence.app.RecurrenceView;

public interface EndRecurrenceUseCase {

    RecurrenceView handle(EndRecurrenceCommand command);

    record EndRecurrenceCommand(UUID userId, UUID recurrenceId, LocalDate endDate) {
    }
}
