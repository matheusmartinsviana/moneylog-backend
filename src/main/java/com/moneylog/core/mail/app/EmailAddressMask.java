package com.moneylog.core.mail.app;

public final class EmailAddressMask {

    private EmailAddressMask() {
    }

    public static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }
        int at = email.lastIndexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
