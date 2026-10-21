package com.moneylog.core.notification.domain;

import java.util.Locale;

public enum SecurityEvent {
    LOGIN_BLOCKED,
    NEW_LOGIN,
    SETUP_COMPLETED,
    PASSWORD_RESET,
    QUICK_TOKEN_CREATED,
    QUICK_TOKEN_REVOKED,
    QUICK_TOKEN_FAILURE_BURST,
    QUICK_TOKEN_NEW_IP,
    QUICK_ENTRY_CREATED;

    public boolean throttled() {
        return this != QUICK_ENTRY_CREATED;
    }

    public boolean requiresPushEnabled() {
        return this == QUICK_ENTRY_CREATED;
    }

    public boolean isolated() {
        return this == QUICK_ENTRY_CREATED;
    }

    public String titleKey() {
        return "security." + name().toLowerCase(Locale.ROOT);
    }
}
