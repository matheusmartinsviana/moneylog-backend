package com.moneylog.core.auth.app.usecase;

import java.util.List;

import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.ManagedUser;

public class ListUsersUseCaseImpl implements ListUsersUseCase {

    private final UserGateway users;

    public ListUsersUseCaseImpl(UserGateway users) {
        this.users = users;
    }

    @Override
    public List<ManagedUser> handle() {
        return users.listManaged();
    }
}
