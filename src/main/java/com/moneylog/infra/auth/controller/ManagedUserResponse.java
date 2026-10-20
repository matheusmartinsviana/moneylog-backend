package com.moneylog.infra.auth.controller;

import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.auth.domain.ManagedUser;
import com.moneylog.core.auth.domain.UserRole;
import com.moneylog.core.auth.domain.UserStatus;

public record ManagedUserResponse(UUID id, String email, UserRole role, UserStatus status, Instant createdAt, Instant lastLoginAt) {

    public static ManagedUserResponse of(ManagedUser user) {
        return new ManagedUserResponse(user.id(), user.email(), user.role(), user.status(), user.createdAt(), user.lastLoginAt());
    }
}
