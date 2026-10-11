package com.moneylog.core.notification.domain;

import java.util.Locale;

public enum SecurityEvent {
    LOGIN_BLOCKED,
    NEW_LOGIN,
    SETUP_COMPLETED,
    PASSWORD_RESET;

    public String titleKey() {
        return "security." + name().toLowerCase(Locale.ROOT);
    }
}
