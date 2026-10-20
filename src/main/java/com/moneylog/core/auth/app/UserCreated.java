package com.moneylog.core.auth.app;

import java.util.UUID;

public record UserCreated(UUID userId, String email) {
}
