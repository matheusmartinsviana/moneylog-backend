package com.moneylog.infra.security;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.moneylog.core.notification.app.SecurityAlertService;
import com.moneylog.core.notification.domain.SecurityEvent;

public class SecurityAlerts {

    private static final Logger log = LoggerFactory.getLogger("com.moneylog.security.events");

    private final SecurityAlertService service;
    private final Executor executor;
    private final Consumer<Runnable> transaction;

    public SecurityAlerts(SecurityAlertService service, Executor executor) {
        this(service, executor, Runnable::run);
    }

    public SecurityAlerts(SecurityAlertService service, Executor executor, Consumer<Runnable> transaction) {
        this.service = service;
        this.executor = executor;
        this.transaction = transaction;
    }

    public static SecurityAlerts disabled() {
        return new SecurityAlerts(null, null);
    }

    public void raise(SecurityEvent event, String ip, String scope) {
        if (service == null) {
            return;
        }
        try {
            executor.execute(() -> deliver(event, ip, scope));
        } catch (RuntimeException e) {
            log.warn("security_alert event={} status=not_scheduled error={}", event.name().toLowerCase(java.util.Locale.ROOT),
                    e.getClass().getSimpleName());
        }
    }

    private void deliver(SecurityEvent event, String ip, String scope) {
        try {
            transaction.accept(() -> service.notify(event, ip, scope));
        } catch (RuntimeException e) {
            log.warn("security_alert event={} status=failed error={}", event.name().toLowerCase(java.util.Locale.ROOT),
                    e.getClass().getSimpleName());
        }
    }
}
