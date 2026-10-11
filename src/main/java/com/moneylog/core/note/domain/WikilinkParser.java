package com.moneylog.core.note.domain;

import java.util.ArrayList;
import java.util.List;

public final class WikilinkParser {

    private static final int MAX_TARGET = 255;

    private WikilinkParser() {
    }

    public static List<Wikilink> parse(String text) {
        return parse(text, 0);
    }

    public static List<Wikilink> parse(String text, int from) {
        List<int[]> code = MarkdownCode.ranges(text, from);
        List<Wikilink> links = new ArrayList<>();
        int i = from;
        while (true) {
            i = text.indexOf("[[", i);
            if (i < 0) {
                break;
            }
            int[] range = MarkdownCode.rangeAt(code, i);
            if (range != null) {
                i = range[1];
                continue;
            }
            if (i > from && text.charAt(i - 1) == '\\') {
                i += 2;
                continue;
            }
            int close = closing(text, i + 2);
            if (close < 0) {
                i += 2;
                continue;
            }
            Wikilink link = build(text, i, close);
            if (link != null) {
                links.add(link);
            }
            i = close + 2;
        }
        return links;
    }

    private static int closing(String text, int from) {
        for (int k = from; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == ']') {
                return k + 1 < text.length() && text.charAt(k + 1) == ']' ? k : -1;
            }
            if (c == '[' || c == '\n' || c == '\r') {
                return -1;
            }
        }
        return -1;
    }

    private static Wikilink build(String text, int open, int close) {
        int contentStart = open + 2;
        String content = text.substring(contentStart, close);
        boolean embed = open > 0 && text.charAt(open - 1) == '!';
        String alias = null;
        String left = content;
        int pipe = content.indexOf('|');
        if (pipe >= 0) {
            alias = content.substring(pipe + 1).strip();
            left = content.substring(0, pipe);
            if (left.endsWith("\\")) {
                left = left.substring(0, left.length() - 1);
            }
        }
        String section = null;
        int hash = left.indexOf('#');
        if (hash >= 0) {
            section = left.substring(hash + 1).strip();
            left = left.substring(0, hash);
        }
        int lead = 0;
        while (lead < left.length() && Character.isWhitespace(left.charAt(lead))) {
            lead++;
        }
        String target = left.strip();
        if (target.isEmpty() || target.length() > MAX_TARGET) {
            return null;
        }
        int targetStart = contentStart + lead;
        return new Wikilink(embed ? open - 1 : open, close + 2, targetStart, targetStart + target.length(), target,
                section == null || section.isEmpty() ? null : section, alias == null || alias.isEmpty() ? null : alias, embed);
    }
}
