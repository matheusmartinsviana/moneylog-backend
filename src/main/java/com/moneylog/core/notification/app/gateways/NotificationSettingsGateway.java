package com.moneylog.core.notification.app.gateways;

import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.notification.domain.NotificationSettings;

public interface NotificationSettingsGateway {

    Optional<NotificationSettings> find(UUID userId);

    Optional<NotificationSettings> save(NotificationSettings settings, long expectedVersion);

    boolean setPushEnabled(UUID userId, boolean enabled);
}
