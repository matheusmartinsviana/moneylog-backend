package com.moneylog.core.note.domain;

import java.text.Normalizer;
import java.util.Locale;

public final class TextFolding {

    private TextFolding() {
    }

    public static String fold(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.COMBINING_SPACING_MARK) {
                continue;
            }
            out.append(c);
        }
        return Normalizer.normalize(out.toString().toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }
}
