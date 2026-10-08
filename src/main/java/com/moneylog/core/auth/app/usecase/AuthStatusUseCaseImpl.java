package com.moneylog.core.auth.app.usecase;

import com.moneylog.core.auth.app.gateways.PasswordVerifier;
import com.moneylog.core.auth.app.gateways.UserGateway;

public class AuthStatusUseCaseImpl implements AuthStatusUseCase {

    private final UserGateway users;
    private final PasswordVerifier passwordVerifier;

    public AuthStatusUseCaseImpl(UserGateway users, PasswordVerifier passwordVerifier) {
        this.users = users;
        this.passwordVerifier = passwordVerifier;
    }

    @Override
    public AuthStatus handle() {
        boolean required = users.findOwner()
                .flatMap(owner -> users.findAccess(owner.id()))
                .map(access -> access.passwordSetupRequired(passwordVerifier.hasEnvironmentHash()))
                .orElse(false);
        return new AuthStatus(required);
    }
}
