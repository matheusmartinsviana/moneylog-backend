package com.moneylog.core.sk.text;

import com.moneylog.core.sk.exception.BusinessRuleException;

/**
 * Texto livre sem caracteres de controle (B4). {@code \u0000} o Postgres recusa e os demais controles (ESC, BEL...) só
 * servem para estragar relatórios e logs. Só as notas aceitam quebra de linha e tabulação.
 */
public final class PlainText {

    private PlainText() {
    }

    /** {@code true} se há caractere de controle (ISO) que o campo não aceita. */
    public static boolean hasControlCharacters(String value, boolean allowLineBreaks) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && !(allowLineBreaks && (c == '\n' || c == '\r' || c == '\t'))) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code true} se o texto serve para uma linha exibida pela UI (título, id, caminho): sem controle (nem quebra de linha ou tabulação),
     * sem caractere de formatação invisível ({@code bidi}, largura zero), sem separador de linha ou parágrafo e sem surrogate solto.
     */
    public static boolean isSafeSingleLine(String value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int type = Character.getType(c);
            if (Character.isISOControl(c) || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                return false;
            }
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    /** Devolve o próprio texto ou lança {@link TextInvalidCharactersException}. */
    public static String requireClean(String value, String field, boolean allowLineBreaks) {
        if (hasControlCharacters(value, allowLineBreaks)) {
            throw new TextInvalidCharactersException(field);
        }
        return value;
    }

    public static class TextInvalidCharactersException extends BusinessRuleException {
        public TextInvalidCharactersException(String field) {
            super("TEXT_INVALID_CHARACTERS", "O campo '" + field + "' tem caracteres de controle.");
        }
    }
}
