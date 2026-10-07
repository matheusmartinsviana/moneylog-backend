package com.moneylog.infra.notification.push;

import com.moneylog.core.notification.app.gateways.PushSender;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;

/** Padrão ({@code moneylog.push.enabled=false}): nada sai e a central funciona igual (S-173). */
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
