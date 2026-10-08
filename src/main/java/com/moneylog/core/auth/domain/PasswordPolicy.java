package com.moneylog.core.auth.domain;

import java.nio.charset.StandardCharsets;

import com.moneylog.core.auth.exception.AuthExceptions.WeakPasswordException;

/** Regra mínima da senha do dono (ADR-015): 12+ caracteres, no máximo 72 bytes (limite do BCrypt) e diferente do e-mail. */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /** @throws WeakPasswordException se a senha não cumpre a regra (mensagem fixa, sem ecoar a senha) */
    public static void check(String password, String email) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES
                || email != null && password.strip().equalsIgnoreCase(email.strip())) {
            throw new WeakPasswordException();
        }
    }
}
