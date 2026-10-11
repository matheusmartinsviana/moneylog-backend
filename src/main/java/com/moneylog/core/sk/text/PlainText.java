package com.moneylog.core.sk.text;

import com.moneylog.core.sk.exception.BusinessRuleException;

public final class PlainText {

    private PlainText() {
    }

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
