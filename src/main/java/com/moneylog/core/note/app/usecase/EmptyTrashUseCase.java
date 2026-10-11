package com.moneylog.core.note.app.usecase;

import java.util.UUID;

public interface EmptyTrashUseCase {

    int handle(EmptyTrashCommand command);

    record EmptyTrashCommand(UUID userId) {
    }
}
