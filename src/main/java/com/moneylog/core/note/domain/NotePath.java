package com.moneylog.core.note.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.moneylog.core.note.exception.NoteExceptions.NotePathInvalidException;
import com.moneylog.core.note.exception.NoteExceptions.PathProblem;

/**
 * Caminho de uma nota dentro do vault, como numa pasta do Obsidian ({@code reviews/2026-10.md}). Valor imutável e sempre válido (S-162):
 * até 255 caracteres e 10 níveis, extensão {@code .md}, sem {@code ..}, sem barra inicial, sem {@code \}, sem controle nem formatação
 * invisível (bidi, largura zero), sem segmento oculto (começando em ponto), sem caractere que o Windows ou o wikilink não aceitam
 * ({@code <>:"|?*#^[]}) e sem nome reservado do Windows. Unicode em NFC (o macOS gera zips em NFD). A unicidade ignora a caixa ({@link #key()}).
 */
public final class NotePath {

    public static final int MAX_LENGTH = 255;
    public static final int MAX_DEPTH = 10;
    public static final String EXTENSION = ".md";

    private static final String FORBIDDEN = "<>:\"|?*#^[]";
    private static final Set<String> RESERVED = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7",
            "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9", "com\u00b9", "com\u00b2", "com\u00b3", "lpt\u00b9",
            "lpt\u00b2", "lpt\u00b3");

    private final String value;

    private NotePath(String value) {
        this.value = value;
    }

    /** @throws NotePathInvalidException caminho fora das regras; {@code problem().hostile()} indica tentativa de escapar da pasta */
    public static NotePath of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid(PathProblem.EMPTY, "vazio");
        }
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isISOControl(raw.charAt(i))) {
                throw invalid(PathProblem.CONTROL_CHARACTERS, "caractere de controle na posição " + i);
            }
        }
        if (raw.indexOf('\\') >= 0) {
            throw invalid(PathProblem.BACKSLASH, "contém '\\'");
        }
        if (raw.charAt(0) == '/') {
            throw invalid(PathProblem.ABSOLUTE, "começa com '/'");
        }
        // "C:/x" é caminho absoluto do Windows; "Q: dúvida" é só um nome com dois-pontos (válido no macOS/Linux), recusado como caractere proibido
        if (raw.length() >= 3 && raw.charAt(1) == ':' && raw.charAt(2) == '/' && Character.isLetter(raw.charAt(0))) {
            throw invalid(PathProblem.DRIVE_LETTER, "letra de unidade");
        }
        String path = Normalizer.normalize(raw, Normalizer.Form.NFC);
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.equals("..") || segment.equals(".")) {
                throw invalid(PathProblem.TRAVERSAL, "segmento '" + segment + "'");
            }
        }
        if (path.length() > MAX_LENGTH) {
            throw invalid(PathProblem.TOO_LONG, "mais de " + MAX_LENGTH + " caracteres");
        }
        if (segments.length > MAX_DEPTH) {
            throw invalid(PathProblem.TOO_DEEP, "mais de " + MAX_DEPTH + " níveis");
        }
        for (String segment : segments) {
            checkSegment(segment);
        }
        String file = segments[segments.length - 1];
        if (file.length() <= EXTENSION.length() || !file.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
            throw invalid(PathProblem.EXTENSION, "a nota precisa terminar em .md");
        }
        String normalized = path.substring(0, path.length() - EXTENSION.length()) + EXTENSION;
        return new NotePath(normalized);
    }

    public static boolean isValid(String raw) {
        try {
            of(raw);
            return true;
        } catch (NotePathInvalidException e) {
            return false;
        }
    }

    private static void checkSegment(String segment) {
        if (segment.isEmpty()) {
            throw invalid(PathProblem.EMPTY_SEGMENT, "segmento vazio");
        }
        if (segment.charAt(0) == '.') {
            throw invalid(PathProblem.HIDDEN_SEGMENT, "segmento oculto '" + segment + "'");
        }
        if (Character.isWhitespace(segment.charAt(0)) || Character.isWhitespace(segment.charAt(segment.length() - 1))
                || segment.endsWith(".")) {
            throw invalid(PathProblem.SEGMENT_WHITESPACE, "segmento começa ou termina com espaço ou termina com ponto");
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            int type = Character.getType(c);
            boolean lone = Character.isSurrogate(c) && !(Character.isHighSurrogate(c) && i + 1 < segment.length()
                    && Character.isLowSurrogate(segment.charAt(i + 1))) && !(Character.isLowSurrogate(c) && i > 0
                    && Character.isHighSurrogate(segment.charAt(i - 1)));
            if (FORBIDDEN.indexOf(c) >= 0 || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || lone) {
                throw invalid(PathProblem.FORBIDDEN_CHARACTER, "caractere não permitido na posição " + i + " do segmento");
            }
        }
        int dot = segment.indexOf('.');
        // o Windows ignora espaços antes do ponto: "CON .md" é o dispositivo CON
        String stem = (dot < 0 ? segment : segment.substring(0, dot)).strip().toLowerCase(Locale.ROOT);
        if (RESERVED.contains(stem)) {
            throw invalid(PathProblem.RESERVED_NAME, "nome reservado '" + stem + "'");
        }
    }

    private static NotePathInvalidException invalid(PathProblem problem, String detail) {
        return new NotePathInvalidException(problem, detail);
    }

    public String value() {
        return value;
    }

    /** Nome do arquivo sem a extensão ({@code 2026-10}): é por ele que o wikilink resolve. */
    public String name() {
        String file = value.substring(value.lastIndexOf('/') + 1);
        return file.substring(0, file.length() - EXTENSION.length());
    }

    /** Pasta ({@code reviews}) ou vazio na raiz. */
    public String folder() {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? "" : value.substring(0, slash);
    }

    /** Caminho sem a extensão ({@code reviews/2026-10}). */
    public String withoutExtension() {
        return value.substring(0, value.length() - EXTENSION.length());
    }

    /** Chave de unicidade: o caminho sem diferenciar maiúsculas. */
    public String key() {
        return value.toLowerCase(Locale.ROOT);
    }

    /** Caminho sem extensão, sem caixa e sem acento (resolução de wikilinks). */
    public String foldedWithoutExtension() {
        return TextFolding.fold(withoutExtension());
    }

    /** Novo caminho com o mesmo nome em outra pasta ou com outro nome, na mesma pasta. */
    public NotePath withName(String newName) {
        String folder = folder();
        return NotePath.of((folder.isEmpty() ? "" : folder + "/") + newName + EXTENSION);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NotePath other && other.value.equals(value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
