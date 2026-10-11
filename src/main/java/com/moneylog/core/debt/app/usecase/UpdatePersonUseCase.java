package com.moneylog.core.debt.app.usecase;

import java.util.UUID;

import com.moneylog.core.debt.app.PersonView;

public interface UpdatePersonUseCase {

    PersonView handle(UpdatePersonCommand command);

    record UpdatePersonCommand(UUID userId, UUID personId, String name, String notes, long version) {
    }
}
