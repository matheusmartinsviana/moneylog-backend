package com.moneylog.core.auth.app.usecase;

public interface LogoutUseCase {

    void handle(LogoutCommand command);

    record LogoutCommand(String refreshToken) {
    }
}
