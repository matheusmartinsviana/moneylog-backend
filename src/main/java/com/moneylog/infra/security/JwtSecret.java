package com.moneylog.infra.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public final class JwtSecret {

    public static final int MIN_BYTES = 32;

    private JwtSecret() {
    }

    public static final int MIN_DISTINCT_CHARS = 12;
    public static final int MAX_SEQUENTIAL_RUN = 6;
    private static final double MAX_SHARE_OF_ONE_CHAR = 0.25;

    public static SecretKey toKey(String secret, boolean strict) {
        SecretKey key = toKey(secret);
        if (strict) {
            requireEntropy(secret);
        }
        return key;
    }

    static void requireEntropy(String secret) {
        String reason = weakness(secret);
        if (reason != null) {
            throw new IllegalStateException("JWT_SECRET tem baixa entropia (" + reason + "). Gere um com: openssl rand -base64 48");
        }
    }

    static String weakness(String secret) {
        int[] points = secret.codePoints().toArray();
        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (int point : points) {
            counts.merge(point, 1, Integer::sum);
        }
        if (counts.size() < MIN_DISTINCT_CHARS) {
            return "poucos caracteres distintos";
        }
        int most = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (most > points.length * MAX_SHARE_OF_ONE_CHAR) {
            return "um caractere domina o segredo";
        }
        if (repeatsAUnit(points)) {
            return "padrão repetido";
        }
        if (longestSequentialRun(points) > MAX_SEQUENTIAL_RUN) {
            return "sequência trivial";
        }
        return null;
    }

    private static boolean repeatsAUnit(int[] points) {
        for (int unit = 1; unit <= points.length / 2; unit++) {
            if (points.length % unit != 0) {
                continue;
            }
            boolean repeats = true;
            for (int i = unit; i < points.length && repeats; i++) {
                repeats = points[i] == points[i - unit];
            }
            if (repeats) {
                return true;
            }
        }
        return false;
    }

    private static int longestSequentialRun(int[] points) {
        int best = 1;
        int up = 1;
        int down = 1;
        for (int i = 1; i < points.length; i++) {
            up = points[i] == points[i - 1] + 1 ? up + 1 : 1;
            down = points[i] == points[i - 1] - 1 ? down + 1 : 1;
            best = Math.max(best, Math.max(up, down));
        }
        return best;
    }

    public static SecretKey toKey(String secret) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_BYTES) {
            throw new IllegalStateException("JWT_SECRET precisa ter no mínimo " + MIN_BYTES + " bytes (tem " + bytes.length
                    + "). Gere um com: openssl rand -base64 48");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
