package com.moneylog.infra.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Resolve e valida o hash BCrypt do dono. Nunca imprime o hash (nem o parcial) nas mensagens de erro.
 */
public final class OwnerPasswordHash {

    private static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]\\$(\\d{2})\\$[./A-Za-z0-9]{53}$");

    private final String value;
    private final int cost;

    private OwnerPasswordHash(String value, int cost) {
        this.value = value;
        this.cost = cost;
    }

    /** Como {@link #resolve}, mas {@code null} quando nenhum hash foi informado (a senha vive só no banco: ADR-015). */
    public static OwnerPasswordHash resolveOptional(String direct, String file) {
        boolean noFile = file == null || file.isBlank();
        boolean noDirect = direct == null || unquote(direct.strip()).isBlank();
        return noFile && noDirect ? null : resolve(direct, file);
    }

    /** O arquivo, se informado, vale mais que o valor direto. */
    public static OwnerPasswordHash resolve(String direct, String file) {
        String raw;
        if (file != null && !file.isBlank()) {
            try {
                raw = Files.readString(Path.of(file.strip()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("não foi possível ler MONEYLOG_OWNER_PASSWORD_HASH_FILE (" + file.strip() + ")", e);
            }
        } else {
            raw = direct;
        }
        String hash = unquote(raw == null ? "" : raw.strip());
        var matcher = BCRYPT.matcher(hash);
        if (!matcher.matches()) {
            throw new IllegalStateException("MONEYLOG_OWNER_PASSWORD_HASH ausente ou não é um hash BCrypt válido "
                    + "(o '$' foi corrompido por interpolação? Use aspas simples no .env ou MONEYLOG_OWNER_PASSWORD_HASH_FILE).");
        }
        return new OwnerPasswordHash(hash, Integer.parseInt(matcher.group(1)));
    }

    private static String unquote(String text) {
        if (text.length() >= 2 && (text.startsWith("'") && text.endsWith("'") || text.startsWith("\"") && text.endsWith("\""))) {
            return text.substring(1, text.length() - 1).strip();
        }
        return text;
    }

    public String value() {
        return value;
    }

    /** Custo (work factor) do hash do dono; o hash fictício usa o mesmo, para igualar o tempo (S-86). */
    public int cost() {
        return cost;
    }
}
