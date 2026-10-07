package com.moneylog.infra.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Valida o {@code JWT_SECRET} (mínimo de 32 bytes) e monta a chave HS256. O segredo nunca vai para o log. */
public final class JwtSecret {

    public static final int MIN_BYTES = 32;

    private JwtSecret() {
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
