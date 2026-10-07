package com.moneylog.core.recurrence.app.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.recurrence.app.RecurrenceView;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.PaymentMethod;

public interface UpdateRecurrenceUseCase {

    /**
     * Edita os dados do modelo (S-139): valem para as ocorrências ainda não realizadas; as realizadas (movimentações) ficam como
     * estão. Tipo, frequência e início não mudam. O corpo traz o estado completo editável: {@code endDate} nulo remove o fim.
     * {@code version} desatualizada → 409.
     */
    RecurrenceView handle(UpdateRecurrenceCommand command);

    record UpdateRecurrenceCommand(UUID userId, UUID recurrenceId, Money amount, String description, UUID categoryId, UUID accountId,
                                   PaymentMethod method, UUID cardId, LocalDate endDate, long version) {
    }
}
