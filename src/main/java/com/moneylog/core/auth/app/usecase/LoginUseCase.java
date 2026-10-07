package com.moneylog.core.auth.app.usecase;

import com.moneylog.core.auth.app.AuthSession;

public interface LoginUseCase {

    /** @throws com.moneylog.core.auth.exception.AuthExceptions.InvalidCredentialsException e-mail ou senha errados */
    AuthSession handle(LoginCommand command);

    record LoginCommand(String email, String password) {
    }
}
