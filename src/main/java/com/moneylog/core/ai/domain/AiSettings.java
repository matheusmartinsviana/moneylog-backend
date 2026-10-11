package com.moneylog.core.ai.domain;

import java.util.UUID;

public record AiSettings(UUID userId, boolean dailyMessageEnabled) {

    public static AiSettings defaults(UUID userId) {
        return new AiSettings(userId, false);
    }
}
