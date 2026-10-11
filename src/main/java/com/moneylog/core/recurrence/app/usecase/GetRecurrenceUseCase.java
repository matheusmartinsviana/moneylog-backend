package com.moneylog.core.recurrence.app.usecase;

import java.util.UUID;

import com.moneylog.core.recurrence.app.RecurrenceView;

public interface GetRecurrenceUseCase {

    RecurrenceView handle(GetRecurrenceQuery query);

    record GetRecurrenceQuery(UUID userId, UUID recurrenceId) {
    }
}
