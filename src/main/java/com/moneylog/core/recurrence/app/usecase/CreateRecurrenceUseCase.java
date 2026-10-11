package com.moneylog.core.recurrence.app.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.recurrence.app.RecurrenceView;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.IntervalUnit;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.TransactionType;

public interface CreateRecurrenceUseCase {

    RecurrenceView handle(CreateRecurrenceCommand command);

    String IDEMPOTENCY_SCOPE = "POST /v1/recurrences";

    record CreateRecurrenceCommand(UUID userId, TransactionType type, Money amount, String description, UUID categoryId, UUID accountId,
                                   PaymentMethod method, UUID cardId, Frequency frequency, Integer interval, IntervalUnit unit,
                                   LocalDate startDate, LocalDate endDate, Integer maxOccurrences, UUID idempotencyKey,
                                   Integer incomeCompetenceOffsetMonths) {

        public CreateRecurrenceCommand(UUID userId, TransactionType type, Money amount, String description, UUID categoryId, UUID accountId,
                                       PaymentMethod method, UUID cardId, Frequency frequency, Integer interval, IntervalUnit unit,
                                       LocalDate startDate, LocalDate endDate, Integer maxOccurrences) {
            this(userId, type, amount, description, categoryId, accountId, method, cardId, frequency, interval, unit, startDate, endDate,
                    maxOccurrences, null, null);
        }

        public CreateRecurrenceCommand(UUID userId, TransactionType type, Money amount, String description, UUID categoryId, UUID accountId,
                                       PaymentMethod method, UUID cardId, Frequency frequency, Integer interval, IntervalUnit unit,
                                       LocalDate startDate, LocalDate endDate, Integer maxOccurrences, UUID idempotencyKey) {
            this(userId, type, amount, description, categoryId, accountId, method, cardId, frequency, interval, unit, startDate, endDate,
                    maxOccurrences, idempotencyKey, null);
        }
    }
}
