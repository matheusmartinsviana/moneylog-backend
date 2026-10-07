package com.moneylog.infra.security;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * {@code MONEYLOG_TRUSTED_PROXIES} vazio atrás de um proxy (CloudFront/ALB) faz todo cliente aparecer com o IP do proxy:
 * o rate limit do login passa a ser global e quem errar 5 vezes bloqueia o dono por 15 minutos (B11). Com o perfil
 * {@code prod} ativo a aplicação <b>não sobe</b> com a lista vazia; fora de {@code dev}, {@code it} e {@code test} o
 * boot só registra um {@code WARN}. Quem roda sem proxy (local, único nó exposto) usa {@code dev}.
 */
public final class TrustedProxiesGuard {

    private static final Logger log = LoggerFactory.getLogger(TrustedProxiesGuard.class);

    private TrustedProxiesGuard() {
    }

    public static void validate(List<String> trustedProxies, Environment environment) {
        if (!trustedProxies.isEmpty()) {
            return;
        }
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("MONEYLOG_TRUSTED_PROXIES vazio com o perfil 'prod': atrás do proxy todo cliente teria o "
                    + "mesmo IP e o rate limit do login bloquearia o dono. Informe os CIDRs do CloudFront/ALB.");
        }
        if (!environment.acceptsProfiles(Profiles.of("dev", "it", "test"))) {
            log.warn("MONEYLOG_TRUSTED_PROXIES está vazio: X-Forwarded-For é ignorado. Atrás de um proxy, todo cliente aparece com o "
                    + "IP dele e o rate limit do login vira global. Informe os CIDRs dos proxies antes do deploy.");
        }
    }
}
