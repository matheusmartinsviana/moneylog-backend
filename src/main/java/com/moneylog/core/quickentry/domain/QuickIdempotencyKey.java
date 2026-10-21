package com.moneylog.core.quickentry.domain;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

public final class QuickIdempotencyKey {

    private static final Pattern UUID_FORMAT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern OPAQUE = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private QuickIdempotencyKey() {
    }

    public static boolean isValid(String key) {
        return key != null && (UUID_FORMAT.matcher(key).matches() || OPAQUE.matcher(key).matches());
    }

    public static UUID resolve(UUID userId, String key) {
        if (!isValid(key)) {
            throw new IllegalArgumentException("Idempotency-Key fora do formato");
        }
        if (UUID_FORMAT.matcher(key).matches()) {
            return UUID.fromString(key);
        }
        return UUID.nameUUIDFromBytes((userId + ":" + key).getBytes(StandardCharsets.UTF_8));
    }
}
