package com.moneylog.core.recurrence.app.usecase;

import java.util.UUID;

import com.moneylog.core.recurrence.app.RecurrenceView;

public interface GetRecurrenceUseCase {

    /** A recorrência com a próxima ocorrência pendente e o valor mensal estimado. De outro usuário → {@code RECURRENCE_NOT_FOUND}. */
    RecurrenceView handle(GetRecurrenceQuery query);

    record GetRecurrenceQuery(UUID userId, UUID recurrenceId) {
    }
}
