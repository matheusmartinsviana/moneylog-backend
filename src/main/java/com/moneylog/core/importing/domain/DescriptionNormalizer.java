package com.moneylog.core.importing.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normaliza a descrição de uma linha de extrato para a chave de deduplicação (D1 §6): trim, maiúsculas, sem acentos,
 * pontuação vira espaço, espaços colapsados. Dígitos são mantidos.
 *
 * <p>Ex.: {@code "  Ifood *XYZ  - São Paulo "} → {@code "IFOOD XYZ SAO PAULO"}.
 */
public final class DescriptionNormalizer {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** Cabe em {@code normalized_description VARCHAR(500)}; descrições maiores são cortadas (a chave de deduplicação usa o texto cortado). */
    public static final int MAX_LENGTH = 500;

    private DescriptionNormalizer() {
    }

    /** {@code null} vira texto vazio. */
    public static String normalize(String description) {
        if (description == null) {
            return "";
        }
        String withoutAccents = COMBINING_MARKS.matcher(Normalizer.normalize(description, Normalizer.Form.NFD)).replaceAll("");
        String spaced = NOT_LETTER_OR_DIGIT.matcher(withoutAccents.toUpperCase(Locale.ROOT)).replaceAll(" ");
        String result = spaced.strip();
        return result.length() > MAX_LENGTH ? result.substring(0, MAX_LENGTH).strip() : result;
    }
}
