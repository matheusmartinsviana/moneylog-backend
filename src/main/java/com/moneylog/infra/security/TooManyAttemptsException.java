package com.moneylog.infra.security;

import java.time.Duration;

/** Rate limit do login estourado: {@code 429} com {@code Retry-After}. */
public class TooManyAttemptsException extends RuntimeException {

    private final transient Duration retryAfter;

    public TooManyAttemptsException(Duration retryAfter) {
        super("Muitas tentativas de login");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
