package com.moneylog.core.note.app.usecase;

import com.moneylog.core.note.app.NoteView;
import com.moneylog.core.note.app.NoteViewAssembler;
import com.moneylog.core.note.app.gateways.NoteGateway;
import com.moneylog.core.note.exception.NoteExceptions.NoteNotFoundException;

public class GetNoteUseCaseImpl implements GetNoteUseCase {

    private final NoteGateway notes;
    private final NoteViewAssembler views;

    public GetNoteUseCaseImpl(NoteGateway notes, NoteViewAssembler views) {
        this.notes = notes;
        this.views = views;
    }

    @Override
    public NoteView handle(GetNoteQuery q) {
        return views.of(q.userId(), notes.findById(q.userId(), q.noteId()).orElseThrow(() -> new NoteNotFoundException(q.noteId())));
    }
}
