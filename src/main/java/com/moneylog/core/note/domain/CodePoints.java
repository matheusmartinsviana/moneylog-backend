package com.moneylog.core.note.domain;

public final class CodePoints {

    private CodePoints() {
    }

    public static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max));
    }

    public static String ellipsize(String text, int max) {
        if (text == null || text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return truncate(text, max - 1) + "…";
    }
}
