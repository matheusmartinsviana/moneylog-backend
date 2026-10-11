package com.moneylog.core.note.app.usecase;

import java.util.UUID;

import com.moneylog.core.note.app.NoteView;

public interface UpdateNoteUseCase {

    NoteView handle(UpdateNoteCommand command);

    record UpdateNoteCommand(UUID userId, UUID noteId, String body, long version) {
    }
}
