package com.moneylog.core.notification.app;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.auth.domain.UserRole;
import com.moneylog.core.notification.app.gateways.NotificationSettingsGateway;
import com.moneylog.core.notification.app.gateways.PushSender;
import com.moneylog.core.notification.app.gateways.PushSender.PushMessage;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;
import com.moneylog.core.notification.domain.IpMask;
import com.moneylog.core.notification.domain.NotificationSettings;
import com.moneylog.core.notification.domain.SecurityEvent;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;

public class SecurityAlertService {

    private static final Logger log = LoggerFactory.getLogger("com.moneylog.security.events");

    public static final String KIND = "SECURITY_ALERT";

    private final UserGateway users;
    private final PushSubscriptionGateway subscriptions;
    private final NotificationSettingsGateway settings;
    private final PushSender sender;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final Duration throttle;
    private final Map<AlertKey, Instant> lastSent = new HashMap<>();

    private record AlertKey(UUID userId, SecurityEvent event) {
    }

    public SecurityAlertService(UserGateway users, PushSubscriptionGateway subscriptions, NotificationSettingsGateway settings,
                                PushSender sender, IdGenerator ids, TimeProvider time, Duration throttle) {
        this.users = users;
        this.subscriptions = subscriptions;
        this.settings = settings;
        this.sender = sender;
        this.ids = ids;
        this.time = time;
        this.throttle = throttle;
    }

    public int notify(SecurityEvent event, String ip, String scope) {
        if (!sender.enabled()) {
            return 0;
        }
        User owner = users.findByRole(UserRole.OWNER).orElse(null);
        return owner == null ? 0 : notifyUser(owner.id(), event, ip, scope);
    }

    public int notifyEmail(String email, SecurityEvent event, String ip, String scope) {
        if (!sender.enabled()) {
            return 0;
        }
        User target = findActiveByEmail(email);
        return target == null ? 0 : notifyUser(target.id(), event, ip, scope);
    }

    public int notifyUser(UUID userId, SecurityEvent event, String ip, String scope) {
        return notifyUser(userId, event, params(ip, scope));
    }

    public int notifyUser(UUID userId, SecurityEvent event, Map<String, String> params) {
        if (!sender.enabled()) {
            return skipped(event, "sender_disabled");
        }
        if (event.requiresPushEnabled() && !pushEnabledBy(userId)) {
            return skipped(event, "push_disabled_by_user");
        }
        List<PushSubscription> targets = subscriptions.listByUser(userId);
        if (targets.isEmpty()) {
            return skipped(event, "no_subscription");
        }
        if (event.throttled() && !acquire(userId, event)) {
            return skipped(event, "throttled");
        }
        PushMessage message = new PushMessage(ids.next(), KIND, event.titleKey(), new TreeMap<>(params));
        int delivered = 0;
        for (PushSubscription target : targets) {
            PushSender.Result result = sender.send(target, message);
            log.info("security_alert event={} status=sent_attempt result={}", eventName(event), result);
            if (result == PushSender.Result.GONE) {
                subscriptions.deleteByEndpoint(target.endpoint());
            } else if (result == PushSender.Result.SENT) {
                delivered++;
            }
        }
        return delivered;
    }

    private static int skipped(SecurityEvent event, String reason) {
        log.info("security_alert event={} status=skipped reason={}", eventName(event), reason);
        return 0;
    }

    private static String eventName(SecurityEvent event) {
        return event.name().toLowerCase(java.util.Locale.ROOT);
    }

    private boolean pushEnabledBy(UUID userId) {
        return settings.find(userId).map(NotificationSettings::pushEnabled).orElse(false);
    }

    private User findActiveByEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return users.findByEmail(User.normalize(email)).filter(User::isActive).orElse(null);
    }

    private synchronized boolean acquire(UUID userId, SecurityEvent event) {
        Instant now = time.now();
        AlertKey key = new AlertKey(userId, event);
        Instant previous = lastSent.get(key);
        if (previous != null && now.isBefore(previous.plus(throttle))) {
            return false;
        }
        lastSent.put(key, now);
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
