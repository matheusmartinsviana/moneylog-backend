package com.moneylog.core.importing.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.importing.exception.ImportingExceptions.InvalidImportLineException;

/**
 * Chave de deduplicação da importação (D1 §6): SHA-256 (hex) de
 * {@code origem | data | valorCentavos | direção | descriçãoNormalizada (ou FITID) | ordinal}.
 *
 * <ul>
 *   <li>O <b>ordinal</b> é a posição da linha entre as linhas <b>idênticas no mesmo arquivo</b>: dois cafés iguais no
 *       mesmo dia ganham ordinais 1 e 2, então continuam distintos, e reimportar o mesmo arquivo gera as mesmas
 *       chaves (determinístico).</li>
 *   <li>No OFX, o {@code FITID} substitui a descrição quando existe.</li>
 *   <li>Uma linha que o usuário <b>força</b> a importar mesmo sendo duplicata recebe uma chave derivada
 *       ({@link #forcedIn(UUID)}), para não depender de violar a unicidade da chave original.</li>
 * </ul>
 */
public record DedupKey(String value) {

    private static final HexFormat HEX = HexFormat.of();

    public DedupKey {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new InvalidImportLineException("chave de deduplicação inválida: " + value);
        }
    }

    /** Chave de uma linha com o ordinal dela entre as linhas idênticas do arquivo (1 para a primeira). */
    public static DedupKey of(ImportLine line, int ordinal) {
        if (ordinal < 1) {
            throw new InvalidImportLineException("o ordinal começa em 1 (recebido " + ordinal + ").");
        }
        return new DedupKey(sha256(identity(line) + "|" + ordinal));
    }

    /**
     * Gera as chaves de todas as linhas de um arquivo, na mesma ordem, atribuindo o ordinal entre linhas idênticas.
     */
    public static List<DedupKey> forFile(List<ImportLine> lines) {
        Objects.requireNonNull(lines, "lines");
        Map<String, Integer> seen = new HashMap<>();
        List<DedupKey> keys = new ArrayList<>(lines.size());
        for (ImportLine line : lines) {
            int ordinal = seen.merge(identity(line), 1, Integer::sum);
            keys.add(of(line, ordinal));
        }
        return List.copyOf(keys);
    }

    /**
     * Chave usada quando o usuário decide importar uma linha que já existe ({@code DUPLICATE}). É derivada da chave
     * original e do lote, então não colide com a original (nem com a mesma decisão em outro lote) e continua
     * determinística dentro do lote.
     */
    public DedupKey forcedIn(UUID importBatchId) {
        Objects.requireNonNull(importBatchId, "importBatchId");
        return new DedupKey(sha256("FORCED|" + value + "|" + importBatchId));
    }

    /** Componentes que definem "linhas idênticas" (sem o ordinal). */
    private static String identity(ImportLine line) {
        String text = line.fitId() != null ? "FITID:" + line.fitId() : "DESC:" + line.normalizedDescription();
        return String.join("|",
                line.sourceId().toString(),
                line.date().toString(),
                Long.toString(line.amount().cents()),
                line.direction().name(),
                text);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
