package com.moneylog.core.notification.app.gateways;

import java.util.Map;
import java.util.UUID;

import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;

public interface PushSender {

    record PushMessage(UUID notificationId, String kind, String titleKey, Map<String, String> params) {
    }

    enum Result {
        SENT,
        GONE,
        RETRY_LATER,
        FAILED
    }

    Result send(PushSubscription subscription, PushMessage message);

    boolean enabled();

    String publicKey();
}
