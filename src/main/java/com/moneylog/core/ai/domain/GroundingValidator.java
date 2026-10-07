package com.moneylog.core.ai.domain;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aterramento (S-184): o texto do modelo só vale se (a) toda referência de fato existe, (b) todo número, valor ou porcentagem do texto aparece
 * nos {@link AiFacts} (comparação normalizada: {@code 1.283,00}, {@code 1283.00} e {@code 1283} são o mesmo número; {@code 23%} compara 23) e
 * (c) a categoria escolhida, quando há, está entre as permitidas.
 *
 * <p>Política documentada: só algarismos são conferidos. Número por extenso ("dois mil") <b>não</b> é checado; o prompt proíbe e o risco restante
 * é aceito no MVP (o texto continua sem poder alterar nada no razão). Fatos com mês ({@code 2026-10}) liberam 2026 e 10.
 */
public final class GroundingValidator {

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d.,]*\\d|\\d");

    private GroundingValidator() {
    }

    public static boolean isGrounded(String text, Collection<String> factRefs, AiFacts facts) {
        return ungroundedReason(text, factRefs, facts) == null;
    }

    /** {@code null} se aterrado; senão o motivo (sem eco do conteúdo). */
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

    /** Remove separadores de milhar e padroniza o decimal; {@code 1.283,00} e {@code 1283.00} viram {@code 1283}. */
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
