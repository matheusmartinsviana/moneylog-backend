package com.moneylog.core.note.app.usecase;

import java.util.UUID;

import com.moneylog.core.note.app.NoteView;

public interface RestoreNoteUseCase {

    NoteView handle(RestoreNoteCommand command);

    record RestoreNoteCommand(UUID userId, UUID noteId) {
    }
}
