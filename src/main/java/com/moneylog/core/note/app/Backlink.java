package com.moneylog.core.note.app;

import java.util.UUID;

public record Backlink(UUID noteId, String path, String title, String context) {
}
