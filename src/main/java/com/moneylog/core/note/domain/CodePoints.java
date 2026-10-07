package com.moneylog.core.note.domain;

/** Corte de texto por <b>ponto de código</b>: nunca deixa um par substituto (emoji) pela metade. */
public final class CodePoints {

    private CodePoints() {
    }

    /** O texto com no máximo {@code max} pontos de código (o Postgres conta {@code VARCHAR(n)} em caracteres, não em unidades UTF-16). */
    public static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max));
    }

    /** Como {@link #truncate}, mas termina em reticências quando cortou (o resultado tem no máximo {@code max} pontos de código). */
    public static String ellipsize(String text, int max) {
        if (text == null || text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return truncate(text, max - 1) + "…";
    }
}
