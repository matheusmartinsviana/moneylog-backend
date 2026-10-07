package com.moneylog.core.auth.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * O usuário do app (ADR-003): na prática só o dono. A senha não fica no banco (o hash BCrypt vem do ambiente).
 */
public record User(UUID id, String email) {

    public User {
        Objects.requireNonNull(id, "id");
        email = normalize(email);
    }

    /** E-mail sem espaços nas pontas e em minúsculas, para comparar e gravar sempre do mesmo jeito. */
    public static String normalize(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("o e-mail é obrigatório");
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
