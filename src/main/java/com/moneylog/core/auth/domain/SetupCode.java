package com.moneylog.core.auth.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

public final class SetupCode {

    public static final int MIN_PROVIDED_LENGTH = 24;
    public static final int MIN_DISTINCT_CHARS = 10;
    private static final int MAX_SHARE_DIVISOR = 4;

    private static final SecureRandom RANDOM = new SecureRandom();

    private SetupCode() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String weakness(String code) {
        if (code == null || code.strip().length() < MIN_PROVIDED_LENGTH) {
            return "menos de " + MIN_PROVIDED_LENGTH + " caracteres";
        }
        int[] points = code.strip().codePoints().toArray();
        Map<Integer, Integer> counts = new HashMap<>();
        for (int point : points) {
            counts.merge(point, 1, Integer::sum);
        }
        if (counts.size() < MIN_DISTINCT_CHARS) {
            return "poucos caracteres distintos";
        }
        if (counts.values().stream().mapToInt(Integer::intValue).max().orElse(0) * MAX_SHARE_DIVISOR > points.length) {
            return "um caractere domina o código";
        }
        if (repeatsAUnit(points)) {
            return "padrão repetido";
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

    public static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    public static boolean sameHash(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
