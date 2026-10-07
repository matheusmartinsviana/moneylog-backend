package com.moneylog.core.note.app.usecase;

import com.moneylog.core.note.app.Backlink;
import com.moneylog.core.note.app.gateways.NoteGateway;
import com.moneylog.core.note.exception.NoteExceptions.NoteNotFoundException;
import com.moneylog.core.sk.page.Slice;

public class ListBacklinksUseCaseImpl implements ListBacklinksUseCase {

    private final NoteGateway notes;

    public ListBacklinksUseCaseImpl(NoteGateway notes) {
        this.notes = notes;
    }

    @Override
    public Slice<Backlink> handle(ListBacklinksQuery q) {
        notes.findById(q.userId(), q.noteId()).orElseThrow(() -> new NoteNotFoundException(q.noteId()));
        if (q.page().isBeyondAnyData()) {
            return Slice.empty();
        }
        return notes.backlinks(q.userId(), q.noteId(), q.page());
    }
}
