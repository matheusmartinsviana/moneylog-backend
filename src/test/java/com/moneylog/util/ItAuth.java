package com.moneylog.util;

import java.util.UUID;

import org.springframework.boot.test.context.TestConfiguration;

import com.moneylog.core.auth.app.gateways.TokenIssuer;
import com.moneylog.core.auth.app.gateways.UserGateway;

@TestConfiguration(proxyBeanMethods = false)
public class ItAuth {

    private final TokenIssuer tokenIssuer;
    private final UserGateway users;

    public ItAuth(TokenIssuer tokenIssuer, UserGateway users) {
        this.tokenIssuer = tokenIssuer;
        this.users = users;
    }

    public UUID ownerId() {
        return users.findOwner().orElseThrow().id();
    }

    public String ownerBearer() {
        return bearer(ownerId());
    }

    public String bearer(UUID userId) {
        return "Bearer " + tokenIssuer.issueAccessToken(userId).value();
    }
}
