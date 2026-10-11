package com.moneylog.core.finance.app.export;

public final class CsvCells {

    public static final char SEPARATOR = ';';

    private CsvCells() {
    }

    public static String text(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String safe = startsFormula(value.charAt(0)) ? "'" + value : value;
        boolean needsQuotes = safe.indexOf(SEPARATOR) >= 0 || safe.indexOf('"') >= 0 || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0;
        return needsQuotes ? '"' + safe.replace("\"", "\"\"") + '"' : safe;
    }

    public static String amount(String plain) {
        return plain.replace('.', ',');
    }

    static boolean startsFormula(char first) {
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }
}
