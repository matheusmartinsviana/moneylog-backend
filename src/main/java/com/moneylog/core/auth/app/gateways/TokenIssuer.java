package com.moneylog.core.auth.app.gateways;

import java.time.Instant;

import com.moneylog.core.auth.domain.User;

public interface TokenIssuer {

    AccessToken issueAccessToken(User user);

    String newRefreshTokenValue();

    record AccessToken(String value, Instant expiresAt) {
    }
}
