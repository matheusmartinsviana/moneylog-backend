package com.moneylog.core.notification.app;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.notification.app.gateways.PushSender;
import com.moneylog.core.notification.app.gateways.PushSender.PushMessage;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;
import com.moneylog.core.notification.domain.IpMask;
import com.moneylog.core.notification.domain.SecurityEvent;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;

public class SecurityAlertService {

    public static final String KIND = "SECURITY_ALERT";

    private final UserGateway users;
    private final PushSubscriptionGateway subscriptions;
    private final PushSender sender;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final Duration throttle;
    private final Map<SecurityEvent, Instant> lastSent = new EnumMap<>(SecurityEvent.class);

    public SecurityAlertService(UserGateway users, PushSubscriptionGateway subscriptions, PushSender sender, IdGenerator ids,
                                TimeProvider time, Duration throttle) {
        this.users = users;
        this.subscriptions = subscriptions;
        this.sender = sender;
        this.ids = ids;
        this.time = time;
        this.throttle = throttle;
    }

    public int notify(SecurityEvent event, String ip, String scope) {
        if (!sender.enabled()) {
            return 0;
        }
        User owner = users.findOwner().orElse(null);
        if (owner == null) {
            return 0;
        }
        List<PushSubscription> targets = subscriptions.listByUser(owner.id());
        if (targets.isEmpty() || !acquire(event)) {
            return 0;
        }
        PushMessage message = new PushMessage(ids.next(), KIND, event.titleKey(), params(ip, scope));
        int delivered = 0;
        for (PushSubscription target : targets) {
            PushSender.Result result = sender.send(target, message);
            if (result == PushSender.Result.GONE) {
                subscriptions.deleteByEndpoint(target.endpoint());
            } else if (result == PushSender.Result.SENT) {
                delivered++;
            }
        }
        return delivered;
    }

    private synchronized boolean acquire(SecurityEvent event) {
        Instant now = time.now();
        Instant previous = lastSent.get(event);
        if (previous != null && now.isBefore(previous.plus(throttle))) {
            return false;
        }
        lastSent.put(event, now);
        return true;
    }

    private static Map<String, String> params(String ip, String scope) {
        Map<String, String> params = new TreeMap<>();
        if (ip != null) {
            params.put("ip", IpMask.mask(ip));
        }
        if (scope != null && !scope.isBlank()) {
            params.put("scope", scope);
        }
        return params;
    }
}
