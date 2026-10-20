package com.moneylog.core.auth.app;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.app.gateways.TokenIssuer;
import com.moneylog.core.auth.domain.RefreshToken;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.sk.id.IdGenerator;

public final class SessionOpener {

    private final TokenIssuer tokenIssuer;
    private final RefreshTokenGateway refreshTokens;
    private final IdGenerator ids;
    private final Duration refreshTtl;
    private final Duration maxSessionAge;

    public static final Duration DEFAULT_MAX_SESSION_AGE = Duration.ofDays(90);

    public SessionOpener(TokenIssuer tokenIssuer, RefreshTokenGateway refreshTokens, IdGenerator ids, Duration refreshTtl) {
        this(tokenIssuer, refreshTokens, ids, refreshTtl, DEFAULT_MAX_SESSION_AGE);
    }

    public SessionOpener(TokenIssuer tokenIssuer, RefreshTokenGateway refreshTokens, IdGenerator ids, Duration refreshTtl,
                         Duration maxSessionAge) {
        this.maxSessionAge = maxSessionAge;
        this.tokenIssuer = tokenIssuer;
        this.refreshTokens = refreshTokens;
        this.ids = ids;
        this.refreshTtl = refreshTtl;
    }

    public AuthSession open(User user, UUID familyId, Instant now) {
        return open(user, familyId, now, now);
    }

    public AuthSession open(User user, UUID familyId, Instant familyStartedAt, Instant now) {
        String rawRefresh = tokenIssuer.newRefreshTokenValue();
        Instant ceiling = familyStartedAt.plus(maxSessionAge);
        Instant refreshExpiresAt = now.plus(refreshTtl);
        if (refreshExpiresAt.isAfter(ceiling)) {
            refreshExpiresAt = ceiling;
        }
        UUID tokenId = ids.next();
        refreshTokens.insert(RefreshToken.issue(tokenId, user.id(), familyId, RefreshTokenHash.of(rawRefresh), now, refreshExpiresAt, familyStartedAt));
        return new AuthSession(user, tokenIssuer.issueAccessToken(user), rawRefresh, tokenId, refreshExpiresAt);
    }
}
