package com.moneylog.core.note.app.usecase;

import java.util.List;

import com.moneylog.core.note.app.NoteView;
import com.moneylog.core.note.app.NoteViewAssembler;
import com.moneylog.core.note.app.VaultIndex;
import com.moneylog.core.note.app.gateways.NoteGateway;
import com.moneylog.core.note.domain.Note;
import com.moneylog.core.note.exception.NoteExceptions.NoteNotFoundException;
import com.moneylog.core.sk.persistence.Versioned;

public class RestoreNoteUseCaseImpl implements RestoreNoteUseCase {

    private final NoteGateway notes;
    private final NoteViewAssembler views;

    public RestoreNoteUseCaseImpl(NoteGateway notes, NoteViewAssembler views) {
        this.notes = notes;
        this.views = views;
    }

    @Override
    public NoteView handle(RestoreNoteCommand c) {
        notes.lockVault(c.userId());
        Versioned<Note> current = notes.findById(c.userId(), c.noteId()).orElseThrow(() -> new NoteNotFoundException(c.noteId()));
        Note note = current.value();
        note.restore();
        VaultIndex.requireFree(notes.activeIndex(c.userId()), note.path(), note.id());
        notes.save(note);
        VaultIndex.refreshWikilinks(notes, c.userId(), List.of(note.path()));
        return views.of(c.userId(), notes.findById(c.userId(), c.noteId()).orElseThrow(() -> new NoteNotFoundException(c.noteId())));
    }
}
