package com.moneylog.core.note.app.usecase;

import com.moneylog.core.note.app.NoteQuery;
import com.moneylog.core.note.app.NoteSummary;
import com.moneylog.core.note.app.gateways.NoteGateway;
import com.moneylog.core.note.domain.NoteTags;
import com.moneylog.core.sk.page.Slice;

public class ListNotesUseCaseImpl implements ListNotesUseCase {

    private final NoteGateway notes;

    public ListNotesUseCaseImpl(NoteGateway notes) {
        this.notes = notes;
    }

    @Override
    public Slice<NoteSummary> handle(NoteQuery query) {
        if (query.page().isBeyondAnyData()) {
            return Slice.empty();
        }
        NoteQuery effective = query;
        if (query.tag() != null) {
            // a tag da busca é normalizada como as gravadas (minúsculas, sem acento); uma tag que não existe não casa com nada
            java.util.Optional<String> tag = NoteTags.normalize(query.tag());
            if (tag.isEmpty()) {
                return Slice.empty();
            }
            effective = new NoteQuery(query.userId(), query.q(), tag.get(), query.folder(), query.linkedType(), query.linkedId(), query.linkedKey(),
                    query.status(), query.sort(), query.page());
        }
        return notes.search(effective);
    }
}
