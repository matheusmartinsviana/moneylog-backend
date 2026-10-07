package com.moneylog.core.note.app.usecase;

import java.util.UUID;

import com.moneylog.core.note.app.NoteView;

public interface GetNoteUseCase {

    /** A nota (ativa ou na lixeira) com o corpo e o estado dos vínculos. Inexistente ou de outro usuário → {@code NOTE_NOT_FOUND}. */
    NoteView handle(GetNoteQuery query);

    record GetNoteQuery(UUID userId, UUID noteId) {
    }
}
