package com.moneylog.infra.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code moneylog.auth.*} (ADR-003).
 *
 * @param accessTokenTtl  vida do JWT de acesso (15 min)
 * @param refreshTtl      vida do refresh token (30 dias)
 * @param reuseGrace      janela de tolerância na detecção de reuso do refresh (30 s, S-83)
 * @param cookieSecure    atributo {@code Secure} do cookie; {@code false} só no plano B de dev (S-85)
 * @param maxLoginFailures falhas de login por IP na janela antes do {@code 429}
 * @param loginWindow     janela do rate limit
 * @param maxSessionAge   teto absoluto da família de refresh tokens, contado do login (90 dias, B7)
 * @param refreshRetention quanto tempo um refresh expirado fica na tabela antes da limpeza (7 dias, B7)
 */
@ConfigurationProperties(prefix = "moneylog.auth")
public record MoneylogAuthProperties(
        @DefaultValue("PT15M") Duration accessTokenTtl,
        @DefaultValue("P30D") Duration refreshTtl,
        @DefaultValue("PT30S") Duration reuseGrace,
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("5") int maxLoginFailures,
        @DefaultValue("PT15M") Duration loginWindow,
        @DefaultValue("P90D") Duration maxSessionAge,
        @DefaultValue("P7D") Duration refreshRetention) {
}
