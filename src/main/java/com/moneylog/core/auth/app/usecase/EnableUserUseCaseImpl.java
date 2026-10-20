package com.moneylog.core.auth.app.usecase;

import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.ManagedUser;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.auth.exception.UserManagementExceptions.UserNotFoundException;
import com.moneylog.core.sk.time.TimeProvider;

public class EnableUserUseCaseImpl implements EnableUserUseCase {

    private final UserGateway users;
    private final TimeProvider time;

    public EnableUserUseCaseImpl(UserGateway users, TimeProvider time) {
        this.users = users;
        this.time = time;
    }

    @Override
    public ManagedUser handle(EnableUserCommand command) {
        User user = users.findById(command.userId()).orElseThrow(() -> new UserNotFoundException(command.userId()));
        User enabled = user.enabled();
        users.updateStatus(enabled.id(), enabled.status(), time.now());
        return users.findManagedById(user.id()).orElseThrow(() -> new UserNotFoundException(command.userId()));
    }
}
