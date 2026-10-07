package com.moneylog.core.auth.app.usecase;

import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.sk.time.TimeProvider;

public class PurgeExpiredRefreshTokensUseCaseImpl implements PurgeExpiredRefreshTokensUseCase {

    private final RefreshTokenGateway refreshTokens;
    private final TimeProvider time;

    public PurgeExpiredRefreshTokensUseCaseImpl(RefreshTokenGateway refreshTokens, TimeProvider time) {
        this.refreshTokens = refreshTokens;
        this.time = time;
    }

    @Override
    public int handle(PurgeCommand command) {
        return refreshTokens.deleteExpiredBefore(time.now().minus(command.retention()));
    }
}
