package com.moneylog.core.auth.app;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.app.gateways.TokenIssuer;
import com.moneylog.core.auth.domain.RefreshToken;
import com.moneylog.core.sk.id.IdGenerator;

/**
 * Abre um par (access + refresh) numa família de refresh tokens. Compartilhado por login e refresh.
 */
public final class SessionOpener {

    private final TokenIssuer tokenIssuer;
    private final RefreshTokenGateway refreshTokens;
    private final IdGenerator ids;
    private final Duration refreshTtl;
    private final Duration maxSessionAge;

    /** Teto absoluto padrão da família (B7). */
    public static final Duration DEFAULT_MAX_SESSION_AGE = Duration.ofDays(90);

    public SessionOpener(TokenIssuer tokenIssuer, RefreshTokenGateway refreshTokens, IdGenerator ids, Duration refreshTtl) {
        this(tokenIssuer, refreshTokens, ids, refreshTtl, DEFAULT_MAX_SESSION_AGE);
    }

    /**
     * @param maxSessionAge teto absoluto da família: nenhum refresh dela vive além de {@code início da família + teto},
     *                      por mais que seja rotacionado (B7)
     */
    public SessionOpener(TokenIssuer tokenIssuer, RefreshTokenGateway refreshTokens, IdGenerator ids, Duration refreshTtl,
                         Duration maxSessionAge) {
        this.maxSessionAge = maxSessionAge;
        this.tokenIssuer = tokenIssuer;
        this.refreshTokens = refreshTokens;
        this.ids = ids;
        this.refreshTtl = refreshTtl;
    }

    /** Primeiro par de uma família nova (login): a família começa agora. */
    public AuthSession open(UUID userId, UUID familyId, Instant now) {
        return open(userId, familyId, now, now);
    }

    public AuthSession open(UUID userId, UUID familyId, Instant familyStartedAt, Instant now) {
        String rawRefresh = tokenIssuer.newRefreshTokenValue();
        Instant ceiling = familyStartedAt.plus(maxSessionAge);
        Instant refreshExpiresAt = now.plus(refreshTtl);
        if (refreshExpiresAt.isAfter(ceiling)) {
            refreshExpiresAt = ceiling;
        }
        UUID tokenId = ids.next();
        refreshTokens.insert(RefreshToken.issue(tokenId, userId, familyId, RefreshTokenHash.of(rawRefresh), now, refreshExpiresAt, familyStartedAt));
        return new AuthSession(userId, tokenIssuer.issueAccessToken(userId), rawRefresh, tokenId, refreshExpiresAt);
    }
}
