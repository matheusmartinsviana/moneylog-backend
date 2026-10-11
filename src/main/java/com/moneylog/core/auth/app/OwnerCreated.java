package com.moneylog.core.auth.app;

import java.util.UUID;

public record OwnerCreated(UUID userId, String email) {
}
