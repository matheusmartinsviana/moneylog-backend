package com.moneylog.core.auth.app;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.auth.app.gateways.TokenIssuer.AccessToken;
import com.moneylog.core.auth.domain.User;

public record AuthSession(User user, AccessToken accessToken, String refreshToken, UUID refreshTokenId,
                          Instant refreshExpiresAt) {

    public AuthSession {
        Objects.requireNonNull(user, "user");
    }

    public UUID userId() {
        return user.id();
    }
}
