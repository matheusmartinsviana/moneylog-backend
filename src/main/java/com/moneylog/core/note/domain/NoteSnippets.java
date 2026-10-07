package com.moneylog.core.note.domain;

/** Trechos de texto puro (nunca HTML) para contexto de backlink e resultado de busca. */
public final class NoteSnippets {

    private static final String ELLIPSIS = "…";

    private NoteSnippets() {
    }

    /** Até {@code max} caracteres em torno de {@code [start, end)}, com espaços e quebras de linha colapsados. */
    public static String around(String text, int start, int end, int max) {
        int length = end - start;
        if (length >= max) {
            return limit(collapse(text.substring(start, end)).strip(), max);
        }
        int budget = max - length - 2 * ELLIPSIS.length();
        int after = Math.min(text.length() - end, budget / 2);
        int before = Math.min(start, budget - after);
        after = Math.min(text.length() - end, budget - before);
        int from = safeStart(text, start - before);
        int to = safeEnd(text, end + after);
        String prefix = from > 0 ? ELLIPSIS : "";
        String suffix = to < text.length() ? ELLIPSIS : "";
        return limit(prefix + collapse(text.substring(from, to)).strip() + suffix, max);
    }

    /**
     * Trecho em torno da primeira ocorrência de {@code query} (sem diferenciar caixa nem acento); sem ocorrência, o início do texto.
     */
    public static String aroundTerm(String text, String query, int max) {
        String folded = TextFolding.fold(query);
        if (!folded.isEmpty()) {
            StringBuilder hay = new StringBuilder(text.length());
            int[] origin = new int[text.length() * 3 + 1];
            for (int i = 0; i < text.length(); i++) {
                String piece = TextFolding.fold(String.valueOf(text.charAt(i)));
                for (int k = 0; k < piece.length(); k++) {
                    if (hay.length() < origin.length) {
                        origin[hay.length()] = i;
                        hay.append(piece.charAt(k));
                    }
                }
            }
            int at = hay.indexOf(folded);
            if (at >= 0) {
                int start = origin[at];
                int end = Math.min(text.length(), origin[at + folded.length() - 1] + 1);
                return around(text, start, end, max);
            }
        }
        return head(text, max);
    }

    /** Início do texto, colapsado, com até {@code max} caracteres. */
    public static String head(String text, int max) {
        return limit(collapse(text).strip(), max);
    }

    static String collapse(String text) {
        return text.replaceAll("[\\s\\u0085\\u2028\\u2029]+", " ");
    }

    private static int safeStart(String text, int pos) {
        return pos > 0 && pos < text.length() && Character.isLowSurrogate(text.charAt(pos)) ? pos - 1 : pos;
    }

    private static int safeEnd(String text, int pos) {
        return pos > 0 && pos < text.length() && Character.isLowSurrogate(text.charAt(pos)) ? pos + 1 : pos;
    }

    private static String limit(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int cut = max;
        if (Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return text.substring(0, cut);
    }
}
