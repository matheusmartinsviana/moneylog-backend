package com.moneylog.core.note.app.usecase;

import com.moneylog.core.note.app.NoteQuery;
import com.moneylog.core.note.app.NoteSummary;
import com.moneylog.core.sk.page.Slice;

public interface ListNotesUseCase {

    /**
     * Lista e busca (S-163): sem {@code q}, as mais recentes; com {@code q}, {@code unaccent} + {@code ILIKE} em título, caminho e corpo,
     * título antes de caminho antes de corpo. Filtros por tag, pasta e vínculo. A lixeira só com {@code status=TRASHED}. Nunca devolve o corpo.
     */
    Slice<NoteSummary> handle(NoteQuery query);
}
