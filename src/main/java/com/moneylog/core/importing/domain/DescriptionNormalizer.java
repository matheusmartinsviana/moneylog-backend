package com.moneylog.core.importing.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class DescriptionNormalizer {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    public static final int MAX_LENGTH = 500;

    private DescriptionNormalizer() {
    }

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
