package com.moneylog.core.auth.domain;

import java.time.Instant;
import java.util.UUID;

public record ManagedUser(UUID id, String email, UserRole role, UserStatus status, Instant createdAt, Instant lastLoginAt) {
}
