package com.moneylog.core.note.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.moneylog.core.note.exception.NoteExceptions.NoteFrontmatterInvalidException;

public final class Frontmatter {

    public static final int MAX_BYTES = 64 * 1024;
    public static final String MONEYLOG_KEY = "moneylog";

    private Frontmatter() {
    }

    public record Block(int yamlStart, int yamlEnd, int closeStart, int end, String newline) {
    }

    public record LinkRef(String type, String id, String key, String path, String state) {
        public LinkRef(String type, String id, String key, String path) {
            this(type, id, key, path, null);
        }
    }

    public record Parsed(Block block, Map<String, Object> data) {

        public boolean present() {
            return block != null;
        }

        public int bodyStart() {
            return block == null ? 0 : block.end();
        }

        public Optional<String> title() {
            Object title = data.get("title");
            if (title instanceof String text && !text.isBlank()) {
                return Optional.of(text.strip());
            }
            return Optional.empty();
        }

        public List<String> rawTags() {
            Object tags = data.get("tags");
            if (tags == null) {
                tags = data.get("tag");
            }
            List<String> out = new ArrayList<>();
            if (tags instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null && !(item instanceof Map<?, ?>) && !(item instanceof List<?>)) {
                        out.add(String.valueOf(item));
                    }
                }
            } else if (tags instanceof String text) {
                for (String part : text.split("[,\\s]+")) {
                    if (!part.isBlank()) {
                        out.add(part);
                    }
                }
            }
            return out;
        }

        public boolean hasMoneylogKey() {
            return data.containsKey(MONEYLOG_KEY);
        }

        public List<LinkRef> moneylogLinks() {
            Object moneylog = data.get(MONEYLOG_KEY);
            if (moneylog == null) {
                return List.of();
            }
            if (!(moneylog instanceof Map<?, ?> map)) {
                throw new NoteFrontmatterInvalidException("'moneylog' precisa ser um mapa.");
            }
            Object links = map.get("links");
            if (links == null) {
                return List.of();
            }
            if (!(links instanceof List<?> list)) {
                throw new NoteFrontmatterInvalidException("'moneylog.links' precisa ser uma lista.");
            }
            List<LinkRef> refs = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> entry) || entry.get("type") == null) {
                    throw new NoteFrontmatterInvalidException("cada vínculo precisa de 'type'.");
                }
                refs.add(new LinkRef(scalar(entry.get("type")), scalar(entry.get("id")), scalar(entry.get("key")), scalar(entry.get("path")),
                        scalar(entry.get("state"))));
            }
            return refs;
        }

        private static String scalar(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                throw new NoteFrontmatterInvalidException("valor de vínculo precisa ser texto.");
            }
            return String.valueOf(value);
        }
    }

    public static Optional<Block> locate(String text) {
        return locate(text, true);
    }

    public static String dropBlock(String text) {
        return locate(text, false).map(block -> text.substring(block.end())).orElse(text);
    }

    private static Optional<Block> locate(String text, boolean enforceLimit) {
        if (!text.startsWith("---")) {
            return Optional.empty();
        }
        int firstEnd = text.indexOf('\n');
        if (firstEnd < 0 || !text.substring(3, firstEnd).isBlank()) {
            return Optional.empty();
        }
        String newline = firstEnd > 0 && text.charAt(firstEnd - 1) == '\r' ? "\r\n" : "\n";
        int yamlStart = firstEnd + 1;
        int lineStart = yamlStart;
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            int contentEnd = lineEnd < 0 ? text.length() : lineEnd;
            String line = text.substring(lineStart, contentEnd).stripTrailing();
            if (line.equals("---") || line.equals("...")) {
                int end = lineEnd < 0 ? text.length() : lineEnd + 1;
                int yamlEnd = lineStart;
                if (enforceLimit && utf8Length(text, yamlStart, yamlEnd) > MAX_BYTES) {
                    throw new NoteFrontmatterInvalidException("acima de " + MAX_BYTES + " bytes.");
                }
                return Optional.of(new Block(yamlStart, yamlEnd, lineStart, end, newline));
            }
            if (lineEnd < 0) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        return Optional.empty();
    }

    public static Parsed parse(String text) {
        Optional<Block> block;
        try {
            block = locate(text);
        } catch (NoteFrontmatterInvalidException tooLarge) {
            return new Parsed(null, Map.of());
        }
        if (block.isEmpty()) {
            return new Parsed(null, Map.of());
        }
        Block b = block.get();
        try {
            return new Parsed(b, parseYaml(text.substring(b.yamlStart(), b.yamlEnd())));
        } catch (NoteFrontmatterInvalidException notFrontmatter) {
            return new Parsed(null, Map.of());
        }
    }

    static Map<String, Object> parseYaml(String yaml) {
        if (yaml.isBlank()) {
            return Map.of();
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(MAX_BYTES);
        options.setNestingDepthLimit(8);
        options.setProcessComments(false);
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(yaml);
        } catch (RuntimeException | StackOverflowError e) {
            throw new NoteFrontmatterInvalidException("YAML ilegível ou não permitido (" + e.getClass().getSimpleName() + ").");
        }
        if (loaded == null) {
            return Map.of();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new NoteFrontmatterInvalidException("o frontmatter precisa ser um mapa de chaves.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Collections.unmodifiableMap(out);
    }

    public static String stripMoneylog(String text) {
        Parsed parsed = parse(text);
        if (!parsed.present()) {
            return text;
        }
        Block b = parsed.block();
        String yaml = text.substring(b.yamlStart(), b.yamlEnd());
        String[] lines = yaml.split("\n", -1);
        StringBuilder kept = new StringBuilder();
        boolean skipping = false;
        boolean removed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean last = i == lines.length - 1;
            if (!last && isMoneylogKeyLine(line)) {
                skipping = true;
                removed = true;
                continue;
            }
            if (skipping) {
                if (line.isBlank() || line.startsWith(" ") || line.startsWith("\t")) {
                    continue;
                }
                skipping = false;
            }
            kept.append(line);
            if (!last) {
                kept.append('\n');
            }
        }
        if (!removed) {
            return text;
        }
        String remaining = kept.toString();
        boolean empty = remaining.isBlank();
        if (empty) {
            return text.substring(b.end());
        }
        return text.substring(0, b.yamlStart()) + remaining + text.substring(b.closeStart());
    }

    private static final Pattern MONEYLOG_KEY_LINE = Pattern.compile("^(?:moneylog|\"moneylog\"|'moneylog')[ \\t]*:(?:[ \\t].*)?$");

    private static boolean isMoneylogKeyLine(String line) {
        return MONEYLOG_KEY_LINE.matcher(line.stripTrailing()).matches();
    }

    public static String withMoneylogLinks(String text, List<LinkRef> links) {
        if (links.isEmpty()) {
            return text;
        }
        Parsed parsed = parse(text);
        Optional<Block> located = parsed.present() && !text.substring(parsed.block().yamlStart(), parsed.block().yamlEnd()).isBlank()
                ? Optional.of(parsed.block()) : Optional.empty();
        String newline = parsed.present() ? parsed.block().newline() : "\n";
        StringBuilder yaml = new StringBuilder(MONEYLOG_KEY).append(':').append(newline).append("  links:").append(newline);
        for (LinkRef link : links) {
            yaml.append("    - { type: ").append(scalar(link.type()));
            if (link.id() != null) {
                yaml.append(", id: ").append(scalar(link.id()));
            }
            if (link.key() != null) {
                yaml.append(", key: ").append(scalar(link.key()));
            }
            if (link.path() != null) {
                yaml.append(", path: ").append(scalar(link.path()));
            }
            if (link.state() != null) {
                yaml.append(", state: ").append(scalar(link.state()));
            }
            yaml.append(" }").append(newline);
        }
        if (located.isEmpty()) {
            return "---" + newline + yaml + "---" + newline + text;
        }
        Block b = located.get();
        String before = text.substring(0, b.closeStart());
        String lead = before.endsWith("\n") || b.yamlStart() == b.closeStart() ? "" : newline;
        return before + lead + yaml + text.substring(b.closeStart());
    }

    static String scalar(String value) {
        if (!value.isEmpty() && value.matches("[A-Za-z0-9_-]+") && !value.matches("-?[0-9_]+") && !value.matches("(?i)true|false|null|yes|no|on|off|y|n")) {
            return value;
        }
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int type = Character.getType(c);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (Character.isISOControl(c) || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static int utf8Length(String text, int from, int to) {
        int bytes = 0;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes++;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c)) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }
}
