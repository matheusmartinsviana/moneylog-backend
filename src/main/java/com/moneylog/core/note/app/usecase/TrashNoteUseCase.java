package com.moneylog.core.note.app.usecase;

import java.util.UUID;

import com.moneylog.core.note.app.NoteView;

public interface TrashNoteUseCase {

    /**
     * Manda a nota para a lixeira (S-24, S-161): ela sai da lista padrão, da busca e dos backlinks, e os wikilinks que apontavam para ela
     * ficam pendentes. Já na lixeira → {@code NOTE_ALREADY_TRASHED}.
     */
    NoteView handle(TrashNoteCommand command);

    record TrashNoteCommand(UUID userId, UUID noteId) {
    }
}
