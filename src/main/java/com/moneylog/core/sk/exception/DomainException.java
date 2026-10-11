package com.moneylog.core.sk.exception;

import java.util.Objects;

public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = requireCode(code);
    }

    protected DomainException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = requireCode(code);
    }

    public String code() {
        return code;
    }

    private static String requireCode(String code) {
        Objects.requireNonNull(code, "code");
        if (!code.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("code deve ser UPPER_SNAKE_CASE: " + code);
        }
        return code;
    }
}
