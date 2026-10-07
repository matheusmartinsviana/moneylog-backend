package com.moneylog.core.importing.parser;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Valor monetário de texto, exato em centavos (nada de {@code double}). Aceita {@code 1.234,56}, {@code 1,234.56}, {@code -82,90},
 * {@code (82,90)}, {@code R$ 82,90}, {@code 82.90}, {@code 1234}, sinal à direita ({@code 82,90-}) e {@code D}/{@code C} finais.
 * Mais de duas casas decimais ou texto que não é número → vazio.
 */
public final class AmountParser {

    private AmountParser() {
    }

    /** Centavos com sinal (negativo = saída), ou vazio se não for um valor. */
    public static Optional<Long> parseCents(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.strip().replace(" ", " ");
        if (text.isEmpty()) {
            return Optional.empty();
        }
        boolean negative = false;
        if (text.startsWith("(") && text.endsWith(")")) {
            negative = true;
            text = text.substring(1, text.length() - 1).strip();
        }
        String upper = text.toUpperCase(java.util.Locale.ROOT);
        if (upper.endsWith(" D") || upper.endsWith("D") && upper.length() > 1 && Character.isDigit(upper.charAt(upper.length() - 2))) {
            negative = true;
            text = text.substring(0, text.length() - 1).strip();
        } else if (upper.endsWith(" C") || upper.endsWith("C") && upper.length() > 1 && Character.isDigit(upper.charAt(upper.length() - 2))) {
            text = text.substring(0, text.length() - 1).strip();
        }
        text = text.replaceFirst("(?i)^(R\\$|BRL)\\s*", "").strip();
        if (text.startsWith("-") || text.startsWith("−")) {
            negative = !negative;
            text = text.substring(1).strip();
        } else if (text.startsWith("+")) {
            text = text.substring(1).strip();
        }
        if (text.endsWith("-")) {
            negative = !negative;
            text = text.substring(0, text.length() - 1).strip();
        }
        text = text.replaceFirst("(?i)^(R\\$|BRL)\\s*", "").strip();
        if (!text.matches("[0-9][0-9.,]*")) {
            return Optional.empty();
        }
        Optional<String> plain = toPlainDecimal(text);
        if (plain.isEmpty()) {
            return Optional.empty();
        }
        try {
            long cents = new BigDecimal(plain.get()).movePointRight(2).longValueExact();
            return Optional.of(negative ? -cents : cents);
        } catch (ArithmeticException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Descobre qual separador é o decimal: o último, se tiver 1 ou 2 dígitos depois; três dígitos depois = milhar. */
    private static Optional<String> toPlainDecimal(String text) {
        int lastComma = text.lastIndexOf(',');
        int lastDot = text.lastIndexOf('.');
        int decimalAt = Math.max(lastComma, lastDot);
        if (decimalAt < 0) {
            return Optional.of(text);
        }
        char decimalChar = text.charAt(decimalAt);
        char otherChar = decimalChar == ',' ? '.' : ',';
        String after = text.substring(decimalAt + 1);
        String before = text.substring(0, decimalAt);
        boolean otherPresent = before.indexOf(otherChar) >= 0;
        boolean sameRepeats = before.indexOf(decimalChar) >= 0;
        if (after.isEmpty() || after.length() > 3) {
            return Optional.empty();
        }
        if (after.length() == 3 && !otherPresent) {
            // 1.234 ou 1,234: separador de milhar (sem parte decimal), desde que seja o único tipo e agrupado de 3 em 3
            if (!text.matches("[0-9]{1,3}([" + decimalChar + "][0-9]{3})+")) {
                return Optional.empty();
            }
            return Optional.of(text.replace(String.valueOf(decimalChar), ""));
        }
        if (after.length() == 3) {
            return Optional.empty();
        }
        if (sameRepeats) {
            return Optional.empty();
        }
        if (otherPresent && !before.matches("[0-9]{1,3}([" + otherChar + "][0-9]{3})+")) {
            return Optional.empty();
        }
        return Optional.of(before.replace(String.valueOf(otherChar), "") + "." + after);
    }
}
