package com.moneylog.core.debt.app.usecase;

import java.util.UUID;

import com.moneylog.core.debt.app.PersonView;

public interface GetPersonUseCase {

    PersonView handle(GetPersonQuery query);

    record GetPersonQuery(UUID userId, UUID personId) {
    }
}
