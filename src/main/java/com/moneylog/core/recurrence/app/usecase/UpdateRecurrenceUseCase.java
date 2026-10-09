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

    // incomeCompetenceOffsetMonths: ausente (null) mantém o atual; 0 limpa; 1 = mês seguinte. Só na recorrência de receita (RECURRENCE_INVALID).

    record UpdateRecurrenceCommand(UUID userId, UUID recurrenceId, Money amount, String description, UUID categoryId, UUID accountId,
                                   PaymentMethod method, UUID cardId, LocalDate endDate, long version,
                                   Integer incomeCompetenceOffsetMonths) {

        /** Sem mexer no deslocamento da competência da renda (mantém o atual). */
        public UpdateRecurrenceCommand(UUID userId, UUID recurrenceId, Money amount, String description, UUID categoryId, UUID accountId,
                                       PaymentMethod method, UUID cardId, LocalDate endDate, long version) {
            this(userId, recurrenceId, amount, description, categoryId, accountId, method, cardId, endDate, version, null);
        }
    }
}
