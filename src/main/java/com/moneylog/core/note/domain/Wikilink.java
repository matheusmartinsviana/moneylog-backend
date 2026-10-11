package com.moneylog.core.note.domain;

import java.util.Locale;

public record Wikilink(int start, int end, int targetStart, int targetEnd, String target, String section, String alias, boolean embed) {

    public String foldedTarget() {
        return TextFolding.fold(withoutExtension(target).replaceFirst("^/+", "").strip());
    }

    public boolean isPathForm() {
        return target.replaceFirst("^/+", "").indexOf('/') >= 0;
    }

    public boolean hasMarkdownExtension() {
        return target.toLowerCase(Locale.ROOT).endsWith(NotePath.EXTENSION);
    }

    static String withoutExtension(String target) {
        return target.toLowerCase(Locale.ROOT).endsWith(NotePath.EXTENSION)
                ? target.substring(0, target.length() - NotePath.EXTENSION.length()) : target;
    }
}
