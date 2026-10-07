package com.moneylog.core.auth.app.gateways;

import java.time.Instant;
import java.util.UUID;

/**
 * Emissão de credenciais: o JWT de acesso (15 min) e o valor opaco do refresh.
 */
public interface TokenIssuer {

    AccessToken issueAccessToken(UUID userId);

    /** Valor aleatório e imprevisível do refresh token (só o hash dele é gravado). */
    String newRefreshTokenValue();

    record AccessToken(String value, Instant expiresAt) {
    }
}
