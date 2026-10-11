package com.moneylog.infra.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SecurityEventLog {

    private static final Logger log = LoggerFactory.getLogger("com.moneylog.security.events");
    private static final int MAX_FIELD = 64;

    private SecurityEventLog() {
    }

    public static void loginSucceeded(String ip, String email) {
        log.info("security_event event=login_succeeded ip={} email={}", clean(ip), maskEmail(email));
    }

    public static void loginFailed(String ip, String email) {
        log.warn("security_event event=login_failed ip={} email={}", clean(ip), maskEmail(email));
    }

    public static void setupFailed(String ip, String email) {
        log.warn("security_event event=setup_failed ip={} email={}", clean(ip), maskEmail(email));
    }

    public static void setupSucceeded(String ip, String email) {
        log.info("security_event event=setup_succeeded ip={} email={}", clean(ip), maskEmail(email));
    }

    public static void blocked(TooManyAttemptsException.Scope scope, String ip, String email) {
        log.warn("security_event event=login_blocked scope={} ip={} email={}", scope.name().toLowerCase(java.util.Locale.ROOT), clean(ip),
                maskEmail(email));
    }

    public static void ownerPasswordReset(String email) {
        log.warn("security_event event=owner_password_reset email={}", maskEmail(email));
    }

    static String maskEmail(String email) {
        if (email == null) {
            return "***";
        }
        String value = clean(email.strip());
        int at = value.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return value.charAt(0) + "***" + value.substring(at);
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && out.length() < MAX_FIELD; i++) {
            char c = value.charAt(i);
            out.append(Character.isISOControl(c) || Character.isWhitespace(c) ? '_' : c);
        }
        return out.toString();
    }
}
