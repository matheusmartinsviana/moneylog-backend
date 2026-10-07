package com.moneylog.infra.security;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

/** Cookie do refresh token: {@code ml_refresh; HttpOnly; Secure; SameSite=Strict; Path=/v1/auth} (ADR-003). */
public class RefreshCookies {

    public static final String NAME = "ml_refresh";
    public static final String PATH = "/v1/auth";

    private final boolean secure;

    public RefreshCookies(boolean secure) {
        this.secure = secure;
    }

    public String issue(String value, Duration maxAge) {
        return build(value, maxAge);
    }

    /** Cookie expirado (logout ou refresh inválido). */
    public String expire() {
        return build("", Duration.ZERO);
    }

    private String build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure).sameSite("Strict").path(PATH)
                .maxAge(maxAge).build().toString();
    }
}
