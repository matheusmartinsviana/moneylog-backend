package com.moneylog.core.note.app.usecase;

import java.util.UUID;

import com.moneylog.core.note.app.NoteView;

public interface GetNoteUseCase {

    NoteView handle(GetNoteQuery query);

    record GetNoteQuery(UUID userId, UUID noteId) {
    }
}
