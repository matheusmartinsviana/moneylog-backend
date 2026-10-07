package com.moneylog.core.debt.app.usecase;

import java.util.UUID;

import com.moneylog.core.debt.app.PersonView;

public interface ArchivePersonUseCase {

    PersonView handle(ArchivePersonCommand command);

    record ArchivePersonCommand(UUID userId, UUID personId) {
    }
}
