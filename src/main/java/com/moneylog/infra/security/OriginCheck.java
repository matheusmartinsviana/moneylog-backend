package com.moneylog.infra.security;

import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Proteção CSRF de {@code /v1/auth/refresh} e {@code /v1/auth/logout} (ADR-003): o refresh viaja em cookie, então o
 * header {@code Origin} precisa estar em {@code MONEYLOG_ALLOWED_ORIGINS}. Sem {@code Origin} também é recusado
 * (navegadores sempre o enviam em POST).
 */
public class OriginCheck {

    private final List<String> allowed;

    public OriginCheck(List<String> allowedOrigins) {
        this.allowed = allowedOrigins.stream().map(OriginCheck::normalize).toList();
    }

    public void ensureAllowed(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank() || !allowed.contains(normalize(origin))) {
            throw new OriginNotAllowedException();
        }
    }

    static String normalize(String origin) {
        String value = origin.strip().toLowerCase(Locale.ROOT);
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
