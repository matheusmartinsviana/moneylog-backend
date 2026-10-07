package com.moneylog.util;

import java.util.UUID;

import org.springframework.boot.test.context.TestConfiguration;

import com.moneylog.core.auth.app.gateways.TokenIssuer;
import com.moneylog.core.auth.app.gateways.UserGateway;

/**
 * Credenciais para os ITs (T22b): emite o JWT pelo mesmo {@link TokenIssuer} da aplicação, com o relógio do contexto
 * (fixo ou avançável), então o token é válido para o relógio do teste. Entra em todo {@code @IntegrationTest}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ItAuth {

    private final TokenIssuer tokenIssuer;
    private final UserGateway users;

    public ItAuth(TokenIssuer tokenIssuer, UserGateway users) {
        this.tokenIssuer = tokenIssuer;
        this.users = users;
    }

    /** Id do dono, criado no boot a partir de {@code moneylog.owner.email}. */
    public UUID ownerId() {
        return users.findOwner().orElseThrow().id();
    }

    /** Valor do header {@code Authorization} do dono. */
    public String ownerBearer() {
        return bearer(ownerId());
    }

    public String bearer(UUID userId) {
        return "Bearer " + tokenIssuer.issueAccessToken(userId).value();
    }
}
