package com.moneylog.core.auth.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record User(UUID id, String email, UserRole role, UserStatus status) {

    public User {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(status, "status");
        email = normalize(email);
    }

    public static User owner(UUID id, String email) {
        return new User(id, email, UserRole.OWNER, UserStatus.ACTIVE);
    }

    public static User member(UUID id, String email) {
        return new User(id, email, UserRole.MEMBER, UserStatus.ACTIVE);
    }

    public static String normalize(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("o e-mail é obrigatório");
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public boolean isOwner() {
        return role == UserRole.OWNER;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public User withEmail(String newEmail) {
        return new User(id, newEmail, role, status);
    }

    public User promotedToOwner() {
        if (!isActive()) {
            throw new IllegalStateException("um usuário desativado não pode ser promovido a dono");
        }
        return new User(id, email, UserRole.OWNER, status);
    }
}
