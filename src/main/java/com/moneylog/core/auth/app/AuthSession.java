package com.moneylog.core.auth.app;

import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.auth.app.gateways.TokenIssuer.AccessToken;

/**
 * Resultado de login e refresh: o access token e o refresh token (valor em claro, para o cookie).
 */
public record AuthSession(UUID userId, AccessToken accessToken, String refreshToken, UUID refreshTokenId,
                          Instant refreshExpiresAt) {
}
