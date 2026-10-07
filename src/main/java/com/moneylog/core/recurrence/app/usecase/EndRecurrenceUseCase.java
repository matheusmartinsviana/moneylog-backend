package com.moneylog.core.recurrence.app.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.recurrence.app.RecurrenceView;

public interface EndRecurrenceUseCase {

    /**
     * Encerra a recorrência numa data (padrão: hoje); nada é apagado. Data anterior ao início ou a uma ocorrência já realizada ou
     * pulada → {@code RECURRENCE_INVALID}.
     */
    RecurrenceView handle(EndRecurrenceCommand command);

    /** @param endDate {@code null} = hoje */
    record EndRecurrenceCommand(UUID userId, UUID recurrenceId, LocalDate endDate) {
    }
}
