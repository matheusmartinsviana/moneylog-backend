package com.moneylog.core.auth.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record User(UUID id, String email) {

    public User {
        Objects.requireNonNull(id, "id");
        email = normalize(email);
    }

    public static String normalize(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("o e-mail é obrigatório");
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
