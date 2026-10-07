package com.moneylog.core.debt.app.usecase;

import java.util.UUID;

import com.moneylog.core.debt.app.PersonView;

public interface CreatePersonUseCase {

    /** Cria a pessoa. O nome não é único (homônimos são aceitos, S-138); nome vazio → {@code PERSON_NAME_REQUIRED}. */
    PersonView handle(CreatePersonCommand command);

    /** Escopo da chave de idempotência (S-244), próprio desta rota. */
    String IDEMPOTENCY_SCOPE = "POST /v1/people";

    /** @param idempotencyKey opcional: com a chave, repetir o mesmo comando devolve a mesma pessoa; outro comando é {@code IDEMPOTENCY_KEY_REUSED} */
    record CreatePersonCommand(UUID userId, String name, String notes, UUID idempotencyKey) {

        /** Sem chave de idempotência. */
        public CreatePersonCommand(UUID userId, String name, String notes) {
            this(userId, name, notes, null);
        }
    }
}
