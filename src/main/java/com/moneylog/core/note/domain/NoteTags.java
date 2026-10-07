package com.moneylog.core.note.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Tags da nota (S-165): as de {@code tags:} do frontmatter e as {@code #tag} do corpo fora de código, normalizadas em minúsculas e sem
 * acento. {@code # título} (com espaço) não é tag; {@code #123} (só números) também não.
 */
public final class NoteTags {

    public static final int MAX_LENGTH = 50;

    private NoteTags() {
    }

    /** Normaliza uma tag escrita ({@code #Revisão Mensal} vira {@code revisao-mensal}); vazio se não é uma tag válida. */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.strip();
        while (text.startsWith("#")) {
            text = text.substring(1);
        }
        String folded = TextFolding.fold(text.strip()).replaceAll("\\s+", "-");
        if (folded.isEmpty() || folded.length() > MAX_LENGTH || !folded.matches("[\\p{L}\\p{N}_/-]+")
                || !folded.matches(".*\\p{L}.*") || folded.startsWith("/") || folded.endsWith("/") || folded.contains("//")) {
            return Optional.empty();
        }
        return Optional.of(folded);
    }

    /**
     * Tags do texto: as do frontmatter ({@code frontmatterTags}) e as {@code #tag} de {@code from} em diante, sem as que estão em código
     * nem dentro de wikilinks ({@code [[nota#seção]]}). O limite de {@link NoteLimits#MAX_TAGS} vale para o conjunto final (frontmatter
     * primeiro, corpo depois). {@code wikilinks} precisa estar em ordem crescente e sem sobreposição, como o {@link WikilinkParser} devolve.
     */
    public static Set<String> extract(String text, int from, List<String> frontmatterTags, List<Wikilink> wikilinks) {
        Set<String> tags = new LinkedHashSet<>();
        for (String raw : frontmatterTags) {
            if (tags.size() >= NoteLimits.MAX_TAGS) {
                break;
            }
            normalize(raw).ifPresent(tags::add);
        }
        List<int[]> code = MarkdownCode.ranges(text, from);
        int i = from;
        int n = text.length();
        int nextLink = 0;
        while (i < n && tags.size() < NoteLimits.MAX_TAGS) {
            int hash = text.indexOf('#', i);
            if (hash < 0) {
                break;
            }
            i = hash + 1;
            int[] range = MarkdownCode.rangeAt(code, hash);
            if (range != null) {
                i = Math.max(i, range[1]);
                continue;
            }
            // os wikilinks vêm em ordem e sem sobreposição, e o # só avança: um ponteiro basta (custo linear, não quadrático)
            while (nextLink < wikilinks.size() && wikilinks.get(nextLink).end() <= hash) {
                nextLink++;
            }
            boolean inLink = nextLink < wikilinks.size() && wikilinks.get(nextLink).start() <= hash;
            if (inLink || !startsTag(text, hash, from)) {
                continue;
            }
            int end = hash + 1;
            while (end < n && isTagChar(text.charAt(end))) {
                end++;
            }
            String candidate = text.substring(hash + 1, end);
            while (candidate.endsWith("-") || candidate.endsWith("/")) {
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            normalize(candidate).ifPresent(tags::add);
            i = Math.max(i, end);
        }
        return tags;
    }

    /** O {@code #} abre tag no começo do texto, depois de espaço ou de {@code (}; e é seguido de caractere de tag. */
    private static boolean startsTag(String text, int hash, int from) {
        if (hash > from) {
            char before = text.charAt(hash - 1);
            if (!Character.isWhitespace(before) && before != '(') {
                return false;
            }
        }
        return hash + 1 < text.length() && isTagChar(text.charAt(hash + 1));
    }

    private static boolean isTagChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '/';
    }
}
