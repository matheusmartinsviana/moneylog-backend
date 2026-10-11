package com.moneylog.core.note.app;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NoteSummary(UUID id, String path, String title, List<String> tags, String snippet, Instant updatedAt, Instant trashedAt,
                          long version) {

    public NoteSummary {
        tags = List.copyOf(tags);
    }
}
