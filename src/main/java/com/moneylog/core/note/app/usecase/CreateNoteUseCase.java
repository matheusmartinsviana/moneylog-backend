package com.moneylog.core.note.app.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.note.app.ExplicitLinkSpec;
import com.moneylog.core.note.app.NoteView;

public interface CreateNoteUseCase {

    String IDEMPOTENCY_SCOPE = "POST /v1/notes";

    NoteView handle(CreateNoteCommand command);

    record CreateNoteCommand(UUID userId, String path, String body, List<ExplicitLinkSpec> links, UUID idempotencyKey) {
    }
}
