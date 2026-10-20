package com.moneylog.core.auth.app.usecase;

import com.moneylog.core.auth.app.gateways.PasswordVerifier;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.UserRole;

public class AuthStatusUseCaseImpl implements AuthStatusUseCase {

    private final UserGateway users;
    private final PasswordVerifier passwordVerifier;

    public AuthStatusUseCaseImpl(UserGateway users, PasswordVerifier passwordVerifier) {
        this.users = users;
        this.passwordVerifier = passwordVerifier;
    }

    @Override
    public AuthStatus handle() {
        boolean required = users.findByRole(UserRole.OWNER)
                .flatMap(owner -> users.findAccess(owner.id()))
                .map(access -> access.passwordSetupRequired(passwordVerifier.hasEnvironmentHash()))
                .orElse(false);
        return new AuthStatus(required);
    }
}
