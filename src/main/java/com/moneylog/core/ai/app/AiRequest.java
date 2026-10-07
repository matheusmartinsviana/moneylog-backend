package com.moneylog.core.ai.app;

import java.util.UUID;

import com.moneylog.core.ai.domain.AiFacts;
import com.moneylog.core.ai.domain.AiPurpose;

/** Pedido já aprovado: o {@code payload} é exatamente o da prévia (hash conferido antes) e {@code facts} é a única fonte de números aceita na resposta. */
public record AiRequest(UUID userId, AiPurpose purpose, String language, String payload, AiFacts facts) {
}
