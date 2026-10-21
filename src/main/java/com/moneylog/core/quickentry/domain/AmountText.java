package com.moneylog.core.quickentry.domain;

import java.util.regex.Pattern;

import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickAmountAmbiguousException;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickAmountInvalidException;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public final class AmountText {

    private static final Pattern ALLOWED = Pattern.compile("[0-9.,]+");
    private static final Pattern PLAIN_INTEGER = Pattern.compile("\\d+");
    private static final Pattern DOT_DECIMAL = Pattern.compile("(\\d+)\\.(\\d{1,2})");
    private static final Pattern THOUSANDS = Pattern.compile("\\d{1,3}(\\.\\d{3})+");
    private static final Pattern COMMA_DECIMALS = Pattern.compile("\\d{1,2}");
    private static final Pattern JSON_NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final int MAX_INTEGER_DIGITS = 9;

    private AmountText() {
    }

    public static Money parseText(String raw) {
        if (raw == null) {
            throw new QuickAmountInvalidException();
        }
        String text = withoutCurrencyAndSpaces(raw);
        if (text.isEmpty() || !ALLOWED.matcher(text).matches()) {
            throw new QuickAmountInvalidException();
        }
        int comma = text.indexOf(',');
        if (comma >= 0) {
            return fromParts(commaIntegerPart(text.substring(0, comma)), commaDecimals(text.substring(comma + 1)));
        }
        if (text.indexOf('.') < 0) {
            requirePlainInteger(text);
            return fromParts(text, "");
        }
        return parseWithoutComma(text);
    }

    public static Money parseJsonNumber(String rawJsonNumber) {
        if (rawJsonNumber == null || !JSON_NUMBER.matcher(rawJsonNumber).matches()) {
            throw new QuickAmountInvalidException();
        }
        int dot = rawJsonNumber.indexOf('.');
        String integer = dot < 0 ? rawJsonNumber : rawJsonNumber.substring(0, dot);
        String decimals = dot < 0 ? "" : stripTrailingZeros(rawJsonNumber.substring(dot + 1));
        if (integer.startsWith("-") || decimals.length() > Money.SCALE) {
            throw new QuickAmountInvalidException();
        }
        return fromParts(integer, decimals);
    }

    public static String format(Money amount) {
        long cents = amount.cents();
        if (cents < 0) {
            throw new IllegalArgumentException("valor negativo não é formatado");
        }
        return formatGrouped(cents / 100) + "," + String.format(java.util.Locale.ROOT, "%02d", cents % 100);
    }

    public static String formatBrl(Money amount) {
        return "R$ " + format(amount);
    }

    private static String withoutCurrencyAndSpaces(String raw) {
        String stripped = raw.strip();
        if (stripped.regionMatches(true, 0, "R$", 0, 2)) {
            stripped = stripped.substring(2);
        }
        StringBuilder out = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String commaIntegerPart(String integerPart) {
        if (integerPart.indexOf(',') >= 0 || integerPart.isEmpty()) {
            throw new QuickAmountInvalidException();
        }
        if (integerPart.indexOf('.') < 0) {
            requirePlainInteger(integerPart);
            return integerPart;
        }
        if (!THOUSANDS.matcher(integerPart).matches()) {
            throw new QuickAmountInvalidException();
        }
        return integerPart.replace(".", "");
    }

    private static String commaDecimals(String decimals) {
        if (!COMMA_DECIMALS.matcher(decimals).matches()) {
            throw new QuickAmountInvalidException();
        }
        return decimals;
    }

    private static Money parseWithoutComma(String text) {
        var decimal = DOT_DECIMAL.matcher(text);
        if (decimal.matches()) {
            return fromParts(decimal.group(1), decimal.group(2));
        }
        if (THOUSANDS.matcher(text).matches()) {
            throw new QuickAmountAmbiguousException();
        }
        throw new QuickAmountInvalidException();
    }

    private static void requirePlainInteger(String text) {
        if (!PLAIN_INTEGER.matcher(text).matches()) {
            throw new QuickAmountInvalidException();
        }
    }

    private static Money fromParts(String integerDigits, String decimalDigits) {
        String integer = integerDigits.startsWith("-") ? integerDigits : stripLeadingZeros(integerDigits);
        if (integer.length() > MAX_INTEGER_DIGITS) {
            throw new QuickAmountInvalidException();
        }
        String decimals = (decimalDigits + "00").substring(0, 2);
        Money amount = Money.of(integer + "." + decimals, CurrencyCode.BRL);
        if (!amount.isPositive() || amount.cents() > AmountLimit.MAX_CENTS) {
            throw new QuickAmountInvalidException();
        }
        return amount;
    }

    private static String stripLeadingZeros(String digits) {
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') {
            i++;
        }
        return digits.substring(i);
    }

    private static String stripTrailingZeros(String digits) {
        int end = digits.length();
        while (end > 0 && digits.charAt(end - 1) == '0') {
            end--;
        }
        return digits.substring(0, end);
    }

    private static String formatGrouped(long units) {
        String digits = Long.toString(units);
        StringBuilder out = new StringBuilder();
        int first = digits.length() % 3;
        if (first > 0) {
            out.append(digits, 0, first);
        }
        for (int i = first; i < digits.length(); i += 3) {
            if (out.length() > 0) {
                out.append('.');
            }
            out.append(digits, i, i + 3);
        }
        return out.toString();
    }
}
