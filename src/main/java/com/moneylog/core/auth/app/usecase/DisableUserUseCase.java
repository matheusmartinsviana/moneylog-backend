package com.moneylog.core.auth.app.usecase;

import java.util.UUID;

import com.moneylog.core.auth.domain.ManagedUser;

public interface DisableUserUseCase {

    ManagedUser handle(DisableUserCommand command);

    record DisableUserCommand(UUID userId) {
    }
}
