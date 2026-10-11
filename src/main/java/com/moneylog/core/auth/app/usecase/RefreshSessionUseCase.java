package com.moneylog.core.auth.app.usecase;

import com.moneylog.core.auth.app.AuthSession;

public interface RefreshSessionUseCase {

    AuthSession handle(RefreshCommand command);

    record RefreshCommand(String refreshToken) {
    }
}
