package com.moneylog.core.ai.domain;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GroundingValidator {

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d.,]*\\d|\\d");

    private GroundingValidator() {
    }

    public static boolean isGrounded(String text, Collection<String> factRefs, AiFacts facts) {
        return ungroundedReason(text, factRefs, facts) == null;
    }

    public static String ungroundedReason(String text, Collection<String> factRefs, AiFacts facts) {
        if (text == null || text.isBlank()) {
            return "EMPTY_TEXT";
        }
        for (String ref : factRefs == null ? List.<String>of() : factRefs) {
            if (!facts.values().containsKey(ref)) {
                return "UNKNOWN_FACT_REF";
            }
        }
        Set<String> allowed = numbersOf(facts.values().values());
        for (String number : numbersOf(List.of(text))) {
            if (!allowed.contains(number)) {
                return "NUMBER_NOT_IN_FACTS";
            }
        }
        return null;
    }

    public static boolean isAllowedCategory(String category, Collection<String> allowed) {
        return category != null && allowed.contains(category);
    }

    static Set<String> numbersOf(Collection<String> texts) {
        Set<String> numbers = new HashSet<>();
        for (String text : texts) {
            Matcher m = NUMBER.matcher(text);
            while (m.find()) {
                numbers.add(normalize(m.group()));
            }
        }
        return numbers;
    }

    static String normalize(String token) {
        int comma = token.lastIndexOf(',');
        int dot = token.lastIndexOf('.');
        String canonical;
        if (comma >= 0 && dot >= 0) {
            char decimal = comma > dot ? ',' : '.';
            char thousands = decimal == ',' ? '.' : ',';
            canonical = token.replace(String.valueOf(thousands), "").replace(decimal, '.');
        } else if (comma >= 0 || dot >= 0) {
            char sep = comma >= 0 ? ',' : '.';
            int count = (int) token.chars().filter(c -> c == sep).count();
            int after = token.length() - token.lastIndexOf(sep) - 1;
            boolean thousands = count > 1 || (after == 3 && !token.startsWith("0"));
            canonical = thousands ? token.replace(String.valueOf(sep), "") : token.replace(sep, '.');
        } else {
            canonical = token;
        }
        try {
            BigDecimal value = new BigDecimal(canonical).stripTrailingZeros();
            return value.signum() == 0 ? "0" : value.toPlainString();
        } catch (NumberFormatException e) {
            return canonical;
        }
    }
}
