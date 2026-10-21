package com.moneylog.core.quickentry.domain;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

public final class QuickIdempotencyKey {

    public static final int RAW_MAX = 200;
    public static final int MIN = 8;
    public static final int MAX = 64;

    private static final Pattern UUID_FORMAT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private QuickIdempotencyKey() {
    }

    public static boolean isValid(String raw) {
        return normalize(raw) != null;
    }

    public static String normalize(String raw) {
        if (raw == null || raw.length() > RAW_MAX) {
            return null;
        }
        String kept = keep(raw);
        if (UUID_FORMAT.matcher(kept).matches() || (kept.length() >= MIN && kept.length() <= MAX)) {
            return kept;
        }
        return null;
    }

    public static String explain(String raw) {
        if (raw == null) {
            return "Cabeçalho obrigatório.";
        }
        int received = raw.codePointCount(0, raw.length());
        if (raw.length() > RAW_MAX) {
            return "Formato inválido: chegaram " + received + " caracteres e o máximo aceito é " + RAW_MAX + ".";
        }
        int kept = keep(raw).length();
        String base = "Formato inválido: chegaram " + received + " caracteres, " + (received - kept) + " inválidos descartados; sobraram " + kept;
        if (kept < MIN) {
            return base + " e o mínimo é " + MIN + ".";
        }
        return base + " e o máximo é " + MAX + ".";
    }

    public static UUID resolve(UUID userId, String raw) {
        String key = normalize(raw);
        if (key == null) {
            throw new IllegalArgumentException("Idempotency-Key fora do formato");
        }
        if (UUID_FORMAT.matcher(key).matches()) {
            return UUID.fromString(key);
        }
        return UUID.nameUUIDFromBytes((userId + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static String keep(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                out.append(c);
            }
        }
        return out.toString();
    }
}
