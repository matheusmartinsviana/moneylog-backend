package com.moneylog.infra.security;

import java.time.Duration;

public class TooManyAttemptsException extends RuntimeException {

    public enum Scope {
        IP, ACCOUNT
    }

    private final transient Duration retryAfter;
    private final Scope scope;

    public TooManyAttemptsException(Duration retryAfter) {
        this(retryAfter, Scope.IP);
    }

    public TooManyAttemptsException(Duration retryAfter, Scope scope) {
        super("Muitas tentativas de login");
        this.retryAfter = retryAfter;
        this.scope = scope;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public Scope scope() {
        return scope;
    }
}
