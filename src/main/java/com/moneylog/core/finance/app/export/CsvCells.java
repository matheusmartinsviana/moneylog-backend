package com.moneylog.core.finance.app.export;

/**
 * Células do CSV (S-153): {@code ;} como separador, escape de aspas e quebras de linha (RFC 4180) e proteção contra injeção de fórmula:
 * texto que começa com {@code =}, {@code +}, {@code -}, {@code @}, tabulação ou retorno de carro recebe um {@code '} na frente, para o
 * Excel e o Sheets não o executarem.
 */
public final class CsvCells {

    public static final char SEPARATOR = ';';

    private CsvCells() {
    }

    /** Texto seguro: neutraliza fórmula e escapa o que o CSV exige. {@code null} vira célula vazia. */
    public static String text(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String safe = startsFormula(value.charAt(0)) ? "'" + value : value;
        boolean needsQuotes = safe.indexOf(SEPARATOR) >= 0 || safe.indexOf('"') >= 0 || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0;
        return needsQuotes ? '"' + safe.replace("\"", "\"\"") + '"' : safe;
    }

    /** Valor monetário em texto com vírgula decimal e sem separador de milhar ({@code 1234,50}). */
    public static String amount(String plain) {
        return plain.replace('.', ',');
    }

    static boolean startsFormula(char first) {
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }
}
