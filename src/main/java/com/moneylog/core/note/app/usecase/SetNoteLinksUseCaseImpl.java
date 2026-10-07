package com.moneylog.core.note.app.usecase;

import com.moneylog.core.note.app.ExplicitLinkValidator;
import com.moneylog.core.note.app.NoteView;
import com.moneylog.core.note.app.NoteViewAssembler;
import com.moneylog.core.note.app.gateways.NoteGateway;
import com.moneylog.core.note.domain.Note;
import com.moneylog.core.note.exception.NoteExceptions.NoteNotFoundException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.persistence.Versioned;

public class SetNoteLinksUseCaseImpl implements SetNoteLinksUseCase {

    private final NoteGateway notes;
    private final ExplicitLinkValidator validator;
    private final NoteViewAssembler views;

    public SetNoteLinksUseCaseImpl(NoteGateway notes, ExplicitLinkValidator validator, NoteViewAssembler views) {
        this.notes = notes;
        this.validator = validator;
        this.views = views;
    }

    @Override
    public NoteView handle(SetNoteLinksCommand c) {
        notes.lockVault(c.userId());
        Versioned<Note> current = notes.findById(c.userId(), c.noteId()).orElseThrow(() -> new NoteNotFoundException(c.noteId()));
        if (current.version() != c.version()) {
            throw new VersionConflictException(c.version(), current.version());
        }
        Note note = current.value();
        note.replaceExplicitLinks(validator.validate(c.userId(), c.links()));
        return views.of(c.userId(), notes.save(note));
    }
}
