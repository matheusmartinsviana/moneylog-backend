package com.moneylog.core.auth.app.usecase;

import com.moneylog.core.auth.app.RefreshTokenHash;
import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.sk.time.TimeProvider;

public class LogoutUseCaseImpl implements LogoutUseCase {

    private final RefreshTokenGateway refreshTokens;
    private final TimeProvider time;

    public LogoutUseCaseImpl(RefreshTokenGateway refreshTokens, TimeProvider time) {
        this.refreshTokens = refreshTokens;
        this.time = time;
    }

    @Override
    public void handle(LogoutCommand command) {
        if (command.refreshToken() == null || command.refreshToken().isBlank()) {
            return;
        }
        refreshTokens.findByHashForUpdate(RefreshTokenHash.of(command.refreshToken()))
                .ifPresent(token -> refreshTokens.revokeFamily(token.familyId(), time.now()));
    }
}
