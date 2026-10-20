package com.moneylog.infra.auth.controller;

import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.auth.app.AuthSession;
import com.moneylog.core.auth.domain.User;

public record AuthTokenResponse(String accessToken, Instant expiresAt, AuthUserResponse user) {

    public static AuthTokenResponse of(AuthSession session) {
        return new AuthTokenResponse(session.accessToken().value(), session.accessToken().expiresAt(),
                AuthUserResponse.of(session.user()));
    }

    public record AuthUserResponse(UUID id, String email, String role) {

        static AuthUserResponse of(User user) {
            return new AuthUserResponse(user.id(), user.email(), user.role().name());
        }
    }
}
