package com.moneylog.core.note.app.usecase;

import java.util.UUID;

import com.moneylog.core.note.app.NoteView;

public interface UpdateNoteUseCase {

    /**
     * Troca o texto da nota; tags, título e wikilinks são recalculados, os vínculos explícitos ficam. {@code version} desatualizada →
     * {@code VERSION_CONFLICT}; nota na lixeira → {@code NOTE_TRASHED}.
     */
    NoteView handle(UpdateNoteCommand command);

    record UpdateNoteCommand(UUID userId, UUID noteId, String body, long version) {
    }
}
