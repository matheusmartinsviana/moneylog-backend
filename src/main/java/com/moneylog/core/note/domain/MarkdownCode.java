package com.moneylog.core.note.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Localiza o código do Markdown (blocos cercados por {@code ```} ou {@code ~~~} e trechos entre crases) para que links e tags dentro
 * dele <b>não</b> sejam lidos nem reescritos. Não renderiza nada: só devolve intervalos {@code [início, fim)} do texto.
 */
public final class MarkdownCode {

    private MarkdownCode() {
    }

    /**
     * Intervalos de código a partir de {@code from}, em ordem crescente e sem sobreposição: blocos cercados, blocos <b>indentados</b> (4
     * espaços ou tabulação depois de linha em branco, fora de listas) e trechos entre crases.
     */
    public static List<int[]> ranges(String text, int from) {
        List<int[]> ranges = new ArrayList<>();
        int n = text.length();
        int segmentStart = from;
        for (int[] block : blockRanges(text, from)) {
            inlineSpans(text, segmentStart, block[0], ranges);
            ranges.add(block);
            segmentStart = block[1];
        }
        inlineSpans(text, segmentStart, n, ranges);
        return ranges;
    }

    /** Blocos de código (cercados e indentados), em ordem. */
    private static List<int[]> blockRanges(String text, int from) {
        List<int[]> blocks = new ArrayList<>();
        int n = text.length();
        int i = from;
        boolean previousBlank = true;
        boolean inList = false;
        int indentedStart = -1;
        int indentedEnd = -1;
        while (i < n) {
            int lineEnd = lineEnd(text, i);
            Fence open = openingFence(text, i, lineEnd);
            if (open != null) {
                if (indentedStart >= 0) {
                    blocks.add(new int[] {indentedStart, indentedEnd});
                    indentedStart = -1;
                }
                int end = n;
                int j = Math.min(n, lineEnd + 1);
                while (j < n) {
                    int e = lineEnd(text, j);
                    if (isClosingFence(text, j, e, open)) {
                        end = Math.min(n, e + 1);
                        break;
                    }
                    j = Math.min(n, e + 1);
                }
                blocks.add(new int[] {i, end});
                i = end;
                previousBlank = false;
                inList = false;
                continue;
            }
            int next = Math.min(n, lineEnd + 1);
            if (isBlank(text, i, lineEnd)) {
                previousBlank = true;
            } else if (isIndented(text, i, lineEnd) && !inList && (previousBlank || indentedStart >= 0)) {
                if (indentedStart < 0) {
                    indentedStart = i;
                }
                indentedEnd = next;
                previousBlank = false;
            } else {
                if (indentedStart >= 0) {
                    blocks.add(new int[] {indentedStart, indentedEnd});
                    indentedStart = -1;
                }
                if (isListItem(text, i, lineEnd)) {
                    inList = true;
                } else if (previousBlank && !isIndented(text, i, lineEnd)) {
                    inList = false;
                }
                previousBlank = false;
            }
            i = next;
        }
        if (indentedStart >= 0) {
            blocks.add(new int[] {indentedStart, indentedEnd});
        }
        return blocks;
    }

    private static boolean isBlank(String text, int start, int end) {
        for (int k = start; k < end; k++) {
            char c = text.charAt(k);
            if (c != ' ' && c != '\t' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    /** Quatro espaços ou uma tabulação no começo (a linha em branco não conta). */
    private static boolean isIndented(String text, int start, int end) {
        if (start < end && text.charAt(start) == '\t') {
            return true;
        }
        return end - start >= 4 && text.startsWith("    ", start);
    }

    /** {@code -}, {@code *}, {@code +} ou {@code 1.}/{@code 1)} seguido de espaço, depois de qualquer indentação. */
    private static boolean isListItem(String text, int start, int end) {
        int k = start;
        while (k < end && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) {
            k++;
        }
        if (k >= end) {
            return false;
        }
        char c = text.charAt(k);
        if (c == '-' || c == '*' || c == '+') {
            return k + 1 < end && (text.charAt(k + 1) == ' ' || text.charAt(k + 1) == '\t');
        }
        int digits = 0;
        while (k + digits < end && Character.isDigit(text.charAt(k + digits))) {
            digits++;
        }
        return digits > 0 && digits <= 9 && k + digits + 1 < end && (text.charAt(k + digits) == '.' || text.charAt(k + digits) == ')')
                && (text.charAt(k + digits + 1) == ' ' || text.charAt(k + digits + 1) == '\t');
    }

    /** {@code true} se {@code pos} cai dentro de algum intervalo. */
    public static boolean covers(List<int[]> ranges, int pos) {
        return rangeAt(ranges, pos) != null;
    }

    /** O intervalo que contém {@code pos}, ou {@code null}. */
    public static int[] rangeAt(List<int[]> ranges, int pos) {
        int low = 0;
        int high = ranges.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int[] r = ranges.get(mid);
            if (pos < r[0]) {
                high = mid - 1;
            } else if (pos >= r[1]) {
                low = mid + 1;
            } else {
                return r;
            }
        }
        return null;
    }

    private record Fence(char marker, int length) {
    }

    private static int lineEnd(String text, int from) {
        int idx = text.indexOf('\n', from);
        return idx < 0 ? text.length() : idx;
    }

    private static Fence openingFence(String text, int start, int end) {
        int i = start;
        int indent = 0;
        while (i < end && text.charAt(i) == ' ' && indent < 4) {
            i++;
            indent++;
        }
        if (indent > 3 || i >= end) {
            return null;
        }
        char marker = text.charAt(i);
        if (marker != '`' && marker != '~') {
            return null;
        }
        int run = 0;
        while (i + run < end && text.charAt(i + run) == marker) {
            run++;
        }
        if (run < 3) {
            return null;
        }
        if (marker == '`' && text.substring(i + run, end).indexOf('`') >= 0) {
            return null;
        }
        return new Fence(marker, run);
    }

    private static boolean isClosingFence(String text, int start, int end, Fence open) {
        int i = start;
        int indent = 0;
        while (i < end && text.charAt(i) == ' ' && indent < 4) {
            i++;
            indent++;
        }
        if (indent > 3) {
            return false;
        }
        int run = 0;
        while (i + run < end && text.charAt(i + run) == open.marker()) {
            run++;
        }
        if (run < open.length()) {
            return false;
        }
        for (int k = i + run; k < end; k++) {
            char c = text.charAt(k);
            if (c != ' ' && c != '\t' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    /** Trechos entre crases iguais, sem atravessar linha em branco; {@code \`} escapa a crase. */
    private static void inlineSpans(String text, int from, int to, List<int[]> out) {
        int j = from;
        while (j < to) {
            char c = text.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c != '`') {
                j++;
                continue;
            }
            int run = runLength(text, j, to);
            int pos = j + run;
            int close = -1;
            while (pos < to) {
                char d = text.charAt(pos);
                if (d == '\n' && blankLineFollows(text, pos + 1, to)) {
                    break;
                }
                if (d == '`') {
                    int m = runLength(text, pos, to);
                    if (m == run) {
                        close = pos;
                        break;
                    }
                    pos += m;
                } else {
                    pos++;
                }
            }
            if (close >= 0) {
                out.add(new int[] {j, close + run});
                j = close + run;
            } else {
                j += run;
            }
        }
    }

    private static int runLength(String text, int from, int to) {
        int k = from;
        while (k < to && text.charAt(k) == '`') {
            k++;
        }
        return k - from;
    }

    private static boolean blankLineFollows(String text, int from, int to) {
        int k = from;
        while (k < to && (text.charAt(k) == ' ' || text.charAt(k) == '\t' || text.charAt(k) == '\r')) {
            k++;
        }
        return k >= to || text.charAt(k) == '\n';
    }
}
