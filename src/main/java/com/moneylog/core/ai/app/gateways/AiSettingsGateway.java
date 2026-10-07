package com.moneylog.core.ai.app.gateways;

import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.ai.domain.AiSettings;

public interface AiSettingsGateway {

    Optional<AiSettings> find(UUID userId);

    AiSettings save(AiSettings settings);
}
