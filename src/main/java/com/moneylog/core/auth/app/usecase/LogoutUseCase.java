package com.moneylog.core.auth.app.usecase;

public interface LogoutUseCase {

    /** Revoga a família do refresh token. Token desconhecido não é erro (o logout é idempotente). */
    void handle(LogoutCommand command);

    record LogoutCommand(String refreshToken) {
    }
}
