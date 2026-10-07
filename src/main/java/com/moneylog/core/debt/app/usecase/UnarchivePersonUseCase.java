package com.moneylog.core.debt.app.usecase;

import java.util.UUID;

import com.moneylog.core.debt.app.PersonView;

public interface UnarchivePersonUseCase {

    PersonView handle(UnarchivePersonCommand command);

    record UnarchivePersonCommand(UUID userId, UUID personId) {
    }
}
