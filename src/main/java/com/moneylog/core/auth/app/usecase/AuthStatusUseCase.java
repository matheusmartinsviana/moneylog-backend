package com.moneylog.core.auth.app.usecase;

public interface AuthStatusUseCase {

    /** O dono ainda precisa escolher a senha (primeiro acesso)? Sem dado sensível. */
    AuthStatus handle();

    record AuthStatus(boolean passwordSetupRequired) {
    }
}
