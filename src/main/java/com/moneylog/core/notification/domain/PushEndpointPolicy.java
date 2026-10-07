package com.moneylog.core.notification.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * Quais endpoints de push o servidor aceita chamar (A3, anti-SSRF): só os serviços de push dos navegadores, por HTTPS na porta 443, sem usuário
 * na URL, sem IP literal e sem porta atípica. A lista é fechada; novo serviço entra por mudança de código, não por configuração do usuário.
 */
public final class PushEndpointPolicy {

    public static final int MAX_LENGTH = 500;

    /** Host exato ou, quando começa com ponto, qualquer subdomínio dele. */
    private static final List<String> ALLOWED = List.of("fcm.googleapis.com", ".push.services.mozilla.com", ".notify.windows.com", ".push.apple.com");

    private PushEndpointPolicy() {
    }

    public static boolean isAllowed(String endpoint) {
        if (endpoint == null || endpoint.isBlank() || endpoint.length() > MAX_LENGTH) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException e) {
            return false;
        }
        if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            return false;
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.endsWith(".") || host.indexOf(':') >= 0 || host.startsWith("[")) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.chars().allMatch(c -> Character.isDigit(c) || c == '.')) {
            return false;
        }
        return ALLOWED.stream().anyMatch(a -> a.startsWith(".") ? lower.endsWith(a) : lower.equals(a));
    }
}
