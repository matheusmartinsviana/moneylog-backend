package com.moneylog.core.notification.app.gateways;

import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.notification.domain.NotificationSettings;

public interface NotificationSettingsGateway {

    Optional<NotificationSettings> find(UUID userId);

    /**
     * Grava com travamento otimista: {@code expectedVersion} é a versão que o cliente leu (0 = ainda não existe). Devolve as configurações com a
     * versão nova, ou vazio se a versão não confere (alguém gravou antes).
     */
    Optional<NotificationSettings> save(NotificationSettings settings, long expectedVersion);
}
