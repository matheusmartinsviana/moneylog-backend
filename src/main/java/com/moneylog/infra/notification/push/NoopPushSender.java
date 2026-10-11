package com.moneylog.infra.notification.push;

import com.moneylog.core.notification.app.gateways.PushSender;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;

public class NoopPushSender implements PushSender {

    @Override
    public Result send(PushSubscription subscription, PushMessage message) {
        return Result.FAILED;
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public String publicKey() {
        return null;
    }
}
