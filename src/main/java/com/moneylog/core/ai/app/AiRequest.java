package com.moneylog.core.ai.app;

import java.util.UUID;

import com.moneylog.core.ai.domain.AiFacts;
import com.moneylog.core.ai.domain.AiPurpose;

public record AiRequest(UUID userId, AiPurpose purpose, String language, String payload, AiFacts facts) {
}
