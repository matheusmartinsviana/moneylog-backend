package com.moneylog.core.backup.app;

import java.time.Instant;

/** Um objeto guardado no armazenamento de backup. */
public record StoredBackup(String key, Instant lastModified, long sizeBytes) {
}
