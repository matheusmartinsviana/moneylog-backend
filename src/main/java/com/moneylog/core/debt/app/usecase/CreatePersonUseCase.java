package com.moneylog.core.debt.app.usecase;

import java.util.UUID;

import com.moneylog.core.debt.app.PersonView;

public interface CreatePersonUseCase {

    PersonView handle(CreatePersonCommand command);

    String IDEMPOTENCY_SCOPE = "POST /v1/people";

    record CreatePersonCommand(UUID userId, String name, String notes, UUID idempotencyKey) {

        public CreatePersonCommand(UUID userId, String name, String notes) {
            this(userId, name, notes, null);
        }
    }
}
