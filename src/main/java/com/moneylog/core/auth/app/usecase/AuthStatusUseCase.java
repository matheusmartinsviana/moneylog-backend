package com.moneylog.core.auth.app.usecase;

public interface AuthStatusUseCase {

    AuthStatus handle();

    record AuthStatus(boolean passwordSetupRequired) {
    }
}
