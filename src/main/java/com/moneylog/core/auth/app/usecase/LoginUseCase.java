package com.moneylog.core.auth.app.usecase;

import com.moneylog.core.auth.app.AuthSession;

public interface LoginUseCase {

    AuthSession handle(LoginCommand command);

    record LoginCommand(String email, String password) {
    }
}
