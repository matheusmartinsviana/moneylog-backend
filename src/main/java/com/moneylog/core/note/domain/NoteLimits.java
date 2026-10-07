package com.moneylog.core.note.domain;

/** Limites do vault (S-162, ADR-009). */
public final class NoteLimits {

    /** Tamanho máximo de uma nota em bytes UTF-8 (200 KB). */
    public static final int NOTE_MAX_BYTES = 200 * 1024;
    /** Notas por usuário (ativas e na lixeira). */
    public static final int MAX_NOTES = 5_000;
    /** Vínculos explícitos por nota. */
    public static final int MAX_EXPLICIT_LINKS = 100;
    /** Wikilinks distintos por nota: o texto com mais que isso é recusado (S-271), nenhum link fica fora do índice. */
    public static final int MAX_WIKILINKS = 500;
    public static final int MAX_TAGS = 100;
    public static final int MAX_TITLE_LENGTH = 255;
    /** Trecho de contexto e de busca (backlinks, resultado de busca). */
    public static final int SNIPPET_MAX = 160;
    public static final int QUERY_MAX = 200;

    /** Zip importado: tamanho enviado, entradas e descompressão total (zip bomb). */
    public static final long IMPORT_MAX_ZIP_BYTES = 20L * 1024 * 1024;
    /** Notas do teto do vault mais arquivos acessórios (anexos, {@code .obsidian}): a exportação do vault cheio sempre volta (S-272). */
    public static final int IMPORT_MAX_ENTRIES = MAX_NOTES + 500;
    public static final long IMPORT_MAX_UNCOMPRESSED_BYTES = 300L * 1024 * 1024;

    private NoteLimits() {
    }
}
