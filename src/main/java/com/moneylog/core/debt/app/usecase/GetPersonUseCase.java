package com.moneylog.core.debt.app.usecase;

import java.util.UUID;

import com.moneylog.core.debt.app.PersonView;

public interface GetPersonUseCase {

    /** A pessoa com o saldo e o histórico de dívidas (S-142). Pessoa de outro usuário → {@code PERSON_NOT_FOUND}. */
    PersonView handle(GetPersonQuery query);

    record GetPersonQuery(UUID userId, UUID personId) {
    }
}
