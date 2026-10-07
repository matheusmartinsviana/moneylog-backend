package com.moneylog.core.note.app.usecase;

import java.util.UUID;

import com.moneylog.core.note.app.NoteView;

public interface RestoreNoteUseCase {

    /**
     * Devolve a nota da lixeira. Caminho ocupado por outra nota ativa → {@code NOTE_PATH_TAKEN} (o usuário escolhe outro; a nota segue na
     * lixeira). Não estando na lixeira → {@code NOTE_NOT_TRASHED}.
     */
    NoteView handle(RestoreNoteCommand command);

    record RestoreNoteCommand(UUID userId, UUID noteId) {
    }
}
