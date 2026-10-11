package com.moneylog.core.finance.app.debts.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.debt.app.DebtView;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.sk.money.Money;

public interface CreateDebtUseCase {

    DebtView handle(CreateDebtCommand command);

    String IDEMPOTENCY_SCOPE = "POST /v1/debts";

    record CreateDebtCommand(UUID userId, UUID personId, DebtDirection direction, Money principal, LocalDate date,
                             LocalDate expectedDate, String description, UUID categoryId, UUID idempotencyKey, String creditorName) {

        public CreateDebtCommand(UUID userId, UUID personId, DebtDirection direction, Money principal, LocalDate date,
                                 LocalDate expectedDate, String description, UUID categoryId, UUID idempotencyKey) {
            this(userId, personId, direction, principal, date, expectedDate, description, categoryId, idempotencyKey, null);
        }

        public CreateDebtCommand(UUID userId, UUID personId, DebtDirection direction, Money principal, LocalDate date,
                                 LocalDate expectedDate, String description, UUID categoryId) {
            this(userId, personId, direction, principal, date, expectedDate, description, categoryId, null);
        }
    }
}
