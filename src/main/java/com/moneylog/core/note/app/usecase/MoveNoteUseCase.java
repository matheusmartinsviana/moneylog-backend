package com.moneylog.core.note.app.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.note.app.NoteView;

public interface MoveNoteUseCase {

    /**
     * Renomeia ou move a nota e reescreve, <b>na mesma transação</b>, os wikilinks das outras notas que apontavam para <b>ela</b> (S-158):
     * preserva alias, seção e embed, nunca toca código nem a nota de mesmo nome em outra pasta. Falha em qualquer nota desfaz tudo.
     */
    MoveNoteResult handle(MoveNoteCommand command);

    record MoveNoteCommand(UUID userId, UUID noteId, String newPath, long version) {
    }

    /** A nota movida e as notas cujo texto foi reescrito. */
    record MoveNoteResult(NoteView note, List<RewrittenNote> rewrittenNotes) {
        public MoveNoteResult {
            rewrittenNotes = List.copyOf(rewrittenNotes);
        }
    }

    record RewrittenNote(UUID id, String path, String title) {
    }
}
