package com.moneylog.core.auth.app.usecase;

import java.time.Duration;

/** Apaga os refresh tokens expirados há mais de {@code retention} (B7). Devolve quantos foram apagados. */
public interface PurgeExpiredRefreshTokensUseCase {

    int handle(PurgeCommand command);

    record PurgeCommand(Duration retention) {
    }
}
