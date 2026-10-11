package com.moneylog.core.finance.app.recurrences.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.sk.money.Money;

public interface RealizeOccurrenceUseCase {

    TransactionDetails handle(RealizeOccurrenceCommand command);

    record RealizeOccurrenceCommand(UUID userId, UUID recurrenceId, LocalDate occurrenceDate, Money actualAmount, UUID idempotencyKey,
                                    UUID accountId, com.moneylog.core.transaction.domain.PaymentMethod method) {

        public RealizeOccurrenceCommand(UUID userId, UUID recurrenceId, LocalDate occurrenceDate, Money actualAmount, UUID idempotencyKey) {
            this(userId, recurrenceId, occurrenceDate, actualAmount, idempotencyKey, null, null);
        }
    }

    String IDEMPOTENCY_SCOPE = "POST /v1/recurrences/{id}/occurrences/{date}/realize";
}
