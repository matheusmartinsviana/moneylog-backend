package com.moneylog.infra.security;

import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * Impede que {@code secure=false} chegue à produção (S-85, plano B). O cookie é sempre {@code Secure}, exceto com o
 * perfil {@code dev} ativo <b>e</b> todas as origens permitidas em {@code localhost}/{@code 127.0.0.1}. Em qualquer
 * outro caso a aplicação não sobe.
 */
public final class CookieSecurityGuard {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private CookieSecurityGuard() {
    }

    public static void validate(boolean secure, boolean devProfileActive, List<String> allowedOrigins) {
        if (secure) {
            return;
        }
        if (!devProfileActive) {
            throw new IllegalStateException("moneylog.auth.cookie-secure=false só é aceito com o perfil 'dev' ativo.");
        }
        for (String origin : allowedOrigins) {
            if (!isLocal(origin)) {
                throw new IllegalStateException("moneylog.auth.cookie-secure=false exige que todas as MONEYLOG_ALLOWED_ORIGINS "
                        + "sejam localhost ou 127.0.0.1 (encontrada: " + origin + ").");
            }
        }
    }

    private static boolean isLocal(String origin) {
        try {
            String host = URI.create(origin.strip()).getHost();
            return host != null && LOCAL_HOSTS.contains(host.toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
