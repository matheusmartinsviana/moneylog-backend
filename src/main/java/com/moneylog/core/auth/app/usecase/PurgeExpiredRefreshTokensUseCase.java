package com.moneylog.core.auth.app.usecase;

import java.time.Duration;

public interface PurgeExpiredRefreshTokensUseCase {

    int handle(PurgeCommand command);

    record PurgeCommand(Duration retention) {
    }
}
