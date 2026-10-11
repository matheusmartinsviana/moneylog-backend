package com.moneylog.core.note.app;

import java.util.UUID;

public record StoredWikilink(UUID linkId, UUID noteId, String text, UUID targetNoteId, String nameKey) {
}
