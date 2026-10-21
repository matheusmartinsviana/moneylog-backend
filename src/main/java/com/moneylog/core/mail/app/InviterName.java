package com.moneylog.core.mail.app;

public final class InviterName {

    public static final int MAX_LENGTH = 60;

    private InviterName() {
    }

    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[\\p{Cntrl}\\p{Cf}\\s]+", " ").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH).strip() : cleaned;
    }
}
