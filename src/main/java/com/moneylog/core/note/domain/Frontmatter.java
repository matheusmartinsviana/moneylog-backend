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

/**
 * Frontmatter YAML de uma nota (S-166). <b>Só é frontmatter</b> um bloco {@code ---...---} fechado cujo conteúdo é um mapeamento YAML
 * válido e seguro (ou está vazio); qualquer outra coisa (régua horizontal com texto no meio, YAML quebrado, escalar ou lista, alias,
 * {@code !!tag}, aninhamento ou tamanho acima do limite) é <b>corpo</b> e fica como foi escrito, byte a byte (S-273). A leitura é <b>segura</b>: {@code SafeConstructor} (nenhum tipo arbitrário, {@code !!java...}
 * falha), sem âncoras nem aliases (nem recursivos), no máximo 64 KB e 8 níveis de aninhamento. As demais chaves do frontmatter ficam no
 * <b>texto</b> da nota e por isso são preservadas na exportação sem reserialização. A chave {@code moneylog.links} traz os vínculos
 * tipados (D1 §5) e só é tocada por {@link #stripMoneylog} e {@link #withMoneylogLinks}, que trabalham sobre o texto.
 */
public final class Frontmatter {

    public static final int MAX_BYTES = 64 * 1024;
    public static final String MONEYLOG_KEY = "moneylog";

    private Frontmatter() {
    }

    /** Posição do bloco {@code ---...---} no início do texto. {@code end} é exclusivo e inclui a quebra de linha depois do fechamento. */
    public record Block(int yamlStart, int yamlEnd, int closeStart, int end, String newline) {
    }

    /**
     * Um vínculo declarado em {@code moneylog.links}, como escrito: {@code type} e {@code id} (entidades), {@code key} (resumo mensal,
     * {@code AAAA-MM}) ou {@code path} (nota, pelo caminho, porque o id muda ao importar). {@code state} é informativo e só a exportação o
     * escreve ({@code trashed}: a nota-alvo está na lixeira, S-275); a importação o ignora.
     */
    public record LinkRef(String type, String id, String key, String path, String state) {
        public LinkRef(String type, String id, String key, String path) {
            this(type, id, key, path, null);
        }
    }

    /** Frontmatter lido: o bloco (ou {@code null} se a nota não tem) e os dados. */
    public record Parsed(Block block, Map<String, Object> data) {

        public boolean present() {
            return block != null;
        }

        /** Posição onde começa o corpo depois do frontmatter. */
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

        /** Tags como escritas (lista, texto separado por vírgula ou espaço); a normalização é do {@link NoteTags}. */
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

        /** Vínculos de {@code moneylog.links}; estrutura inválida lança {@link NoteFrontmatterInvalidException}. */
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

    // ------------------------------------------------------------------ localizar e ler

    /**
     * Acha o bloco no início do texto: primeira linha {@code ---} e fechamento {@code ---} ou {@code ...} em linha própria. Sem fechamento,
     * não é frontmatter (como no Obsidian).
     *
     * @throws NoteFrontmatterInvalidException o conteúdo do bloco passa de 64 KB
     */
    public static Optional<Block> locate(String text) {
        return locate(text, true);
    }

    /** Remove o bloco de frontmatter do início do texto, seja ele válido ou não (sem checar o tamanho). Usado na importação. */
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

    /**
     * Localiza e lê o frontmatter. Sem bloco <b>reconhecido</b>, devolve dados vazios e o texto inteiro é corpo: bloco sem fechamento,
     * conteúdo que não é um mapa YAML válido e seguro (régua horizontal com texto, lista, escalar, alias, {@code !!tag}) ou acima de 64 KB.
     */
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

    // ------------------------------------------------------------------ edição sobre o texto

    /**
     * Remove do texto o bloco da chave {@code moneylog} do frontmatter (linhas indentadas que a seguem). Se o frontmatter fica sem
     * conteúdo (só linhas em branco), o bloco inteiro some: um frontmatter vazio pré-existente também some, sem perda de dado. Nada mais é reescrito.
     */
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

    /** A chave no começo da linha, pura ou entre aspas ({@code "moneylog":}), com espaço antes dos dois-pontos. */
    private static final Pattern MONEYLOG_KEY_LINE = Pattern.compile("^(?:moneylog|\"moneylog\"|'moneylog')[ \\t]*:(?:[ \\t].*)?$");

    private static boolean isMoneylogKeyLine(String line) {
        return MONEYLOG_KEY_LINE.matcher(line.stripTrailing()).matches();
    }

    /**
     * Acrescenta {@code moneylog.links} ao frontmatter (cria o bloco se a nota não tem frontmatter reconhecido ou se o dela está vazio; um
     * texto que começa com {@code ---} sem ser frontmatter ganha o bloco na frente). O resto do texto não muda. Pressupõe que o texto
     * ainda não tem a chave {@code moneylog} (as notas gravadas nunca a têm).
     */
    public static String withMoneylogLinks(String text, List<LinkRef> links) {
        if (links.isEmpty()) {
            return text;
        }
        Parsed parsed = parse(text);
        // um bloco vazio ou em branco recebe um bloco NOVO na frente (o original volta intacto quando a importação tira o nosso)
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

    /** Escalar YAML seguro: puro se for só {@code [A-Za-z0-9_-]}, senão entre aspas duplas com escapes. */
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
