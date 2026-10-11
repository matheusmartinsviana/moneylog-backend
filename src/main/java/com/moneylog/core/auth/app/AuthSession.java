package com.moneylog.core.auth.app;

import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.auth.app.gateways.TokenIssuer.AccessToken;

public record AuthSession(UUID userId, AccessToken accessToken, String refreshToken, UUID refreshTokenId,
                          Instant refreshExpiresAt) {
}
