package com.moneylog.core.backup.app;

import java.time.Instant;

public record StoredBackup(String key, Instant lastModified, long sizeBytes) {
}
