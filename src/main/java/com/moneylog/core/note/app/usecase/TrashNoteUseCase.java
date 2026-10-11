package com.moneylog.core.note.app.usecase;

import java.util.UUID;

import com.moneylog.core.note.app.NoteView;

public interface TrashNoteUseCase {

    NoteView handle(TrashNoteCommand command);

    record TrashNoteCommand(UUID userId, UUID noteId) {
    }
}
