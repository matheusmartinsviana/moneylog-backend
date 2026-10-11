package com.moneylog.core.note.domain;

public final class NoteLimits {

    public static final int NOTE_MAX_BYTES = 200 * 1024;
    public static final int MAX_NOTES = 5_000;
    public static final int MAX_EXPLICIT_LINKS = 100;
    public static final int MAX_WIKILINKS = 500;
    public static final int MAX_TAGS = 100;
    public static final int MAX_TITLE_LENGTH = 255;
    public static final int SNIPPET_MAX = 160;
    public static final int QUERY_MAX = 200;

    public static final long IMPORT_MAX_ZIP_BYTES = 20L * 1024 * 1024;
    public static final int IMPORT_MAX_ENTRIES = MAX_NOTES + 500;
    public static final long IMPORT_MAX_UNCOMPRESSED_BYTES = 300L * 1024 * 1024;

    private NoteLimits() {
    }
}
