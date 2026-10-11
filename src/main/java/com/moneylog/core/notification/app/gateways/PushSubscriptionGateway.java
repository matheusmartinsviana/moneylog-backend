package com.moneylog.core.notification.app.gateways;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PushSubscriptionGateway {

    record PushSubscription(UUID id, UUID userId, String endpoint, String p256dh, String auth, String userAgent, Instant createdAt) {
    }

    PushSubscription upsert(PushSubscription subscription);

    int count(UUID userId);

    boolean delete(UUID userId, UUID id);

    void deleteByEndpoint(String endpoint);

    List<PushSubscription> listByUser(UUID userId);
}
