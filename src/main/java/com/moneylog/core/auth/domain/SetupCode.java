package com.moneylog.core.auth.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Código de uso único do primeiro acesso (ADR-015): 256 bits aleatórios; só o SHA-256 vai para o banco. */
public final class SetupCode {

    /** Tamanho mínimo de um código informado pelo operador (variável de ambiente ou secret). */
    public static final int MIN_PROVIDED_LENGTH = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private SetupCode() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 em hexadecimal. O código tem entropia alta, então um hash rápido basta (não é senha escolhida). */
    public static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    /** Comparação em tempo constante entre dois hashes hexadecimais. */
    public static boolean sameHash(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
