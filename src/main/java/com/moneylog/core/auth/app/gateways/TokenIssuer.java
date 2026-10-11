package com.moneylog.core.auth.app.gateways;

import java.time.Instant;
import java.util.UUID;

public interface TokenIssuer {

    AccessToken issueAccessToken(UUID userId);

    String newRefreshTokenValue();

    record AccessToken(String value, Instant expiresAt) {
    }
}
