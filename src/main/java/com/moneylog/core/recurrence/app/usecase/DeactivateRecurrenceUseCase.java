package com.moneylog.core.recurrence.app.usecase;

import java.util.UUID;

import com.moneylog.core.recurrence.app.RecurrenceView;

public interface DeactivateRecurrenceUseCase {

    RecurrenceView handle(DeactivateRecurrenceCommand command);

    record DeactivateRecurrenceCommand(UUID userId, UUID recurrenceId) {
    }
}
