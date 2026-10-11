package com.moneylog.core.auth.app.usecase;

import com.moneylog.core.auth.app.AuthSession;

public interface SetupPasswordUseCase {

    AuthSession handle(SetupPasswordCommand command);

    record SetupPasswordCommand(String email, String setupCode, String newPassword) {
    }
}
