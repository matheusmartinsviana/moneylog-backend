package com.moneylog.core.note.app.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.note.app.NoteView;

public interface MoveNoteUseCase {

    MoveNoteResult handle(MoveNoteCommand command);

    record MoveNoteCommand(UUID userId, UUID noteId, String newPath, long version) {
    }

    record MoveNoteResult(NoteView note, List<RewrittenNote> rewrittenNotes) {
        public MoveNoteResult {
            rewrittenNotes = List.copyOf(rewrittenNotes);
        }
    }

    record RewrittenNote(UUID id, String path, String title) {
    }
}
