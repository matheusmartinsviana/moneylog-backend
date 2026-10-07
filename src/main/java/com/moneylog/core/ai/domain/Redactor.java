package com.moneylog.core.ai.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redator de privacidade (S-182, PR 00 §27): tudo que sai de uma descrição passa por aqui antes de ir à prévia.
 *
 * <ul>
 *   <li>PIX/TED/DOC: o nome do favorecido <b>não vai ao provedor</b>; sai só o tipo da operação ({@code [PIX]});</li>
 *   <li>e-mail, CPF, CNPJ, telefone, cartão (13 a 19 dígitos) e qualquer sequência de 6 dígitos ou mais viram marcadores;</li>
 *   <li>nomes de pessoas cadastradas viram {@code Pessoa A}, {@code Pessoa B}...</li>
 * </ul>
 *
 * Maiúsculas e acentos são normalizados (a saída é em maiúsculas sem acento). {@code types} lista os tipos mascarados, nunca os valores.
 */
public final class Redactor {

    public record Result(String text, Set<String> types) {
    }

    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+\\-]+@[A-Z0-9.\\-]+\\.[A-Z]{2,}");
    private static final Pattern PAYEE_OPERATION = Pattern.compile("(?<![A-Z0-9])(PIX|TED|DOC)(?![A-Z0-9])");
    private static final Pattern DIGIT_RUN = Pattern.compile("\\(?\\d[\\d.\\-/ ()]*\\d");

    private final List<String> personNames;

    /** @param personNames nomes das pessoas cadastradas, na ordem que define "Pessoa A", "Pessoa B"... */
    public Redactor(List<String> personNames) {
        List<String> clean = new ArrayList<>();
        for (String name : personNames) {
            String normalized = normalize(name);
            if (normalized.length() >= 2) {
                clean.add(normalized);
            }
        }
        this.personNames = List.copyOf(clean);
    }

    public Result redact(String description) {
        Set<String> types = new LinkedHashSet<>();
        String text = normalize(description == null ? "" : description);
        Matcher operation = PAYEE_OPERATION.matcher(text);
        if (operation.find()) {
            types.add("PAYEE");
            return new Result("[" + operation.group(1) + "]", types);
        }
        text = replace(EMAIL, text, "[EMAIL]", "EMAIL", types);
        text = redactDigits(text, types);
        for (int i = 0; i < personNames.size(); i++) {
            Matcher m = Pattern.compile("(?<![A-Z0-9])" + Pattern.quote(personNames.get(i)) + "(?![A-Z0-9])").matcher(text);
            if (m.find()) {
                types.add("PERSON");
                text = m.replaceAll(Matcher.quoteReplacement("PESSOA " + label(i)));
            }
        }
        return new Result(text.strip(), types);
    }

    private static String label(int index) {
        return index < 26 ? String.valueOf((char) ('A' + index)) : String.valueOf(index + 1);
    }

    private static String replace(Pattern pattern, String text, String marker, String type, Set<String> types) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return text;
        }
        types.add(type);
        return m.replaceAll(Matcher.quoteReplacement(marker));
    }

    private static String redactDigits(String text, Set<String> types) {
        Matcher m = DIGIT_RUN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String run = m.group();
            int digits = (int) run.chars().filter(Character::isDigit).count();
            String replacement;
            String type;
            if (digits < 6) {
                replacement = run;
                type = null;
            } else if (digits == 14) {
                replacement = "[CNPJ]";
                type = "CNPJ";
            } else if (digits >= 13) {
                replacement = "[CARTAO]";
                type = "CARD";
            } else if (digits >= 10 && run.contains("(")) {
                replacement = "[TEL]";
                type = "PHONE";
            } else if (digits == 11 && run.matches("\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}")) {
                replacement = "[CPF]";
                type = "CPF";
            } else if (digits >= 10 && digits <= 11) {
                replacement = "[TEL]";
                type = "PHONE";
            } else {
                replacement = "[NUM]";
                type = "NUMBER";
            }
            if (type != null) {
                types.add(type);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT).strip();
    }
}
