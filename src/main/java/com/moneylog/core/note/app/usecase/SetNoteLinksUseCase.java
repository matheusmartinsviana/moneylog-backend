package com.moneylog.core.note.app.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.note.app.ExplicitLinkSpec;
import com.moneylog.core.note.app.NoteView;

public interface SetNoteLinksUseCase {

    NoteView handle(SetNoteLinksCommand command);

    record SetNoteLinksCommand(UUID userId, UUID noteId, List<ExplicitLinkSpec> links, long version) {
    }
}
