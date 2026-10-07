package com.moneylog.core.note.app;

/** Ordenação da lista (S-163): relevância (título &gt; caminho &gt; corpo, só com {@code q}), mais recentes, caminho ou título. */
public enum NoteSort {
    RELEVANCE, UPDATED, PATH, TITLE
}
