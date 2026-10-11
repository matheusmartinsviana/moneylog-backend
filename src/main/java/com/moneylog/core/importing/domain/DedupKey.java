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

public record DedupKey(String value) {

    private static final HexFormat HEX = HexFormat.of();

    public DedupKey {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new InvalidImportLineException("chave de deduplicação inválida: " + value);
        }
    }

    public static DedupKey of(ImportLine line, int ordinal) {
        if (ordinal < 1) {
            throw new InvalidImportLineException("o ordinal começa em 1 (recebido " + ordinal + ").");
        }
        return new DedupKey(sha256(identity(line) + "|" + ordinal));
    }

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

    public DedupKey forcedIn(UUID importBatchId) {
        Objects.requireNonNull(importBatchId, "importBatchId");
        return new DedupKey(sha256("FORCED|" + value + "|" + importBatchId));
    }

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
