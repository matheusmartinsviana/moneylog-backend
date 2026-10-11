package com.moneylog.core.insights.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Identidade estável do insight: {@code ins_<fingerprint>}, com {@code fingerprint = SHA-256(usuário | tipo | período | assunto)} (32 primeiros
 * caracteres hexadecimais). O usuário entra no hash: o mesmo insight de dois usuários nunca tem o mesmo id, então id de outro usuário não existe
 * para quem pede (404, sem vazar existência).
 */
public final class InsightIds {

    public static final String PREFIX = "ins_";
    private static final int HEX_LENGTH = 32;

    private InsightIds() {
    }

    public static String fingerprint(UUID userId, Insight insight) {
        String period = insight.period() == null ? "-" : insight.period().kind() + ":" + insight.period().value();
        String raw = userId + "|" + insight.type() + "|" + period + "|" + (insight.subjectId() == null ? "" : insight.subjectId());
        try {
            String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
            return hex.substring(0, HEX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String id(UUID userId, Insight insight) {
        return PREFIX + fingerprint(userId, insight);
    }

    /** O fingerprint de um id público, ou {@code null} se o formato não é de um id de insight. */
    public static String fingerprintOf(String id) {
        if (id == null || !id.matches(PREFIX + "[0-9a-f]{" + HEX_LENGTH + "}")) {
            return null;
        }
        return id.substring(PREFIX.length());
    }
}
