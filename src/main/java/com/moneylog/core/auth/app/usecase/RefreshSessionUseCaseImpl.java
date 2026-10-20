package com.moneylog.core.auth.app.usecase;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.auth.app.AuthSession;
import com.moneylog.core.auth.app.RefreshTokenHash;
import com.moneylog.core.auth.app.SessionOpener;
import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.RefreshToken;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.auth.exception.AuthExceptions.RefreshInvalidException;
import com.moneylog.core.sk.time.TimeProvider;

public class RefreshSessionUseCaseImpl implements RefreshSessionUseCase {

    private final UserGateway users;
    private final RefreshTokenGateway refreshTokens;
    private final SessionOpener sessions;
    private final TimeProvider time;
    private final Duration reuseGrace;

    public RefreshSessionUseCaseImpl(UserGateway users, RefreshTokenGateway refreshTokens, SessionOpener sessions, TimeProvider time,
                                     Duration reuseGrace) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.sessions = sessions;
        this.time = time;
        this.reuseGrace = reuseGrace;
    }

    @Override
    public AuthSession handle(RefreshCommand command) {
        if (command.refreshToken() == null || command.refreshToken().isBlank()) {
            throw new RefreshInvalidException();
        }
        Instant now = time.now();
        String tokenHash = RefreshTokenHash.of(command.refreshToken());
        UUID userId = refreshTokens.findUserIdByHash(tokenHash).orElseThrow(RefreshInvalidException::new);
        User user = users.findByIdLockingShared(userId).filter(User::isActive).orElseThrow(RefreshInvalidException::new);
        RefreshToken token = refreshTokens.findByHashForUpdate(tokenHash).orElseThrow(RefreshInvalidException::new);
        if (token.isRevoked() || token.isExpired(now)) {
            throw new RefreshInvalidException();
        }
        if (!token.isReplaced()) {
            AuthSession session = sessions.open(user, token.familyId(), token.familyStartedAt(), now);
            refreshTokens.markReplaced(token.id(), session.refreshTokenId(), now);
            return session;
        }
        if (Duration.between(token.replacedAt(), now).compareTo(reuseGrace) <= 0) {
            return sessions.open(user, token.familyId(), token.familyStartedAt(), now);
        }
        refreshTokens.revokeFamily(token.familyId(), now);
        throw new RefreshInvalidException();
    }
}
