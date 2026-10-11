package com.moneylog.core.recurrence.app.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.recurrence.app.RecurrenceView;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.PaymentMethod;

public interface UpdateRecurrenceUseCase {

    RecurrenceView handle(UpdateRecurrenceCommand command);

    record UpdateRecurrenceCommand(UUID userId, UUID recurrenceId, Money amount, String description, UUID categoryId, UUID accountId,
                                   PaymentMethod method, UUID cardId, LocalDate endDate, long version,
                                   Integer incomeCompetenceOffsetMonths) {

        public UpdateRecurrenceCommand(UUID userId, UUID recurrenceId, Money amount, String description, UUID categoryId, UUID accountId,
                                       PaymentMethod method, UUID cardId, LocalDate endDate, long version) {
            this(userId, recurrenceId, amount, description, categoryId, accountId, method, cardId, endDate, version, null);
        }
    }
}
