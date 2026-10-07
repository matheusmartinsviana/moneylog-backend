package com.moneylog.core.recurrence.app.usecase;

import java.util.UUID;

import com.moneylog.core.recurrence.app.RecurrenceView;

public interface DeactivateRecurrenceUseCase {

    /** Desativa a recorrência: deixa de gerar ocorrências pendentes e de entrar nos compromissos. Idempotente; nada é apagado. */
    RecurrenceView handle(DeactivateRecurrenceCommand command);

    record DeactivateRecurrenceCommand(UUID userId, UUID recurrenceId) {
    }
}
