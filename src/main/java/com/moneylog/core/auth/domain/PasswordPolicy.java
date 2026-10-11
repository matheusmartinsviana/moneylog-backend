package com.moneylog.core.auth.domain;

import java.nio.charset.StandardCharsets;

import com.moneylog.core.auth.exception.AuthExceptions.WeakPasswordException;

public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void check(String password, String email) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES
                || email != null && password.strip().equalsIgnoreCase(email.strip())) {
            throw new WeakPasswordException();
        }
    }
}
