package com.moneylog.core.ai.domain;

import java.util.UUID;

/** Preferências de IA do usuário (S-190). A chave, o provedor e o modelo vêm só do ambiente do servidor, nunca daqui. */
public record AiSettings(UUID userId, boolean dailyMessageEnabled) {

    public static AiSettings defaults(UUID userId) {
        return new AiSettings(userId, false);
    }
}
