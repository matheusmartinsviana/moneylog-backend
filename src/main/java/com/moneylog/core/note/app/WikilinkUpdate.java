package com.moneylog.core.note.app;

import java.util.UUID;

public record WikilinkUpdate(UUID linkId, UUID targetNoteId, String nameKey) {
}
