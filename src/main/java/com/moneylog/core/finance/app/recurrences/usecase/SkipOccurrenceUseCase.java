package com.moneylog.core.finance.app.recurrences.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.recurrence.app.OccurrenceView;

public interface SkipOccurrenceUseCase {

    /**
     * Pula uma ocorrência (S-140): ela deixa de ser compromisso e <b>não</b> vira movimentação. Já realizada →
     * {@code RECURRENCE_OCCURRENCE_ALREADY_REALIZED}; já pulada → {@code RECURRENCE_OCCURRENCE_SKIPPED}; data que não é ocorrência →
     * {@code RECURRENCE_NOT_AN_OCCURRENCE}.
     */
    OccurrenceView handle(SkipOccurrenceCommand command);

    record SkipOccurrenceCommand(UUID userId, UUID recurrenceId, LocalDate occurrenceDate) {
    }
}
