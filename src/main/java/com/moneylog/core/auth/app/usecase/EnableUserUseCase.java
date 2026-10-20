package com.moneylog.core.auth.app.usecase;

import java.util.UUID;

import com.moneylog.core.auth.domain.ManagedUser;

public interface EnableUserUseCase {

    ManagedUser handle(EnableUserCommand command);

    record EnableUserCommand(UUID userId) {
    }
}
