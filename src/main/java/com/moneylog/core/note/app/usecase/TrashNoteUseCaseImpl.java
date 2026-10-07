package com.moneylog.core.note.app.usecase;

import java.util.List;

import com.moneylog.core.note.app.NoteView;
import com.moneylog.core.note.app.NoteViewAssembler;
import com.moneylog.core.note.app.VaultIndex;
import com.moneylog.core.note.app.gateways.NoteGateway;
import com.moneylog.core.note.domain.Note;
import com.moneylog.core.note.exception.NoteExceptions.NoteNotFoundException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class TrashNoteUseCaseImpl implements TrashNoteUseCase {

    private final NoteGateway notes;
    private final NoteViewAssembler views;
    private final TimeProvider time;

    public TrashNoteUseCaseImpl(NoteGateway notes, NoteViewAssembler views, TimeProvider time) {
        this.notes = notes;
        this.views = views;
        this.time = time;
    }

    @Override
    public NoteView handle(TrashNoteCommand c) {
        notes.lockVault(c.userId());
        Versioned<Note> current = notes.findById(c.userId(), c.noteId()).orElseThrow(() -> new NoteNotFoundException(c.noteId()));
        Note note = current.value();
        note.trash(time.now());
        notes.save(note);
        VaultIndex.refreshWikilinks(notes, c.userId(), List.of(note.path()));
        return views.of(c.userId(), notes.findById(c.userId(), c.noteId()).orElseThrow(() -> new NoteNotFoundException(c.noteId())));
    }
}
