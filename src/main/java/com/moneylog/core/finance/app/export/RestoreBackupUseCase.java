package com.moneylog.core.finance.app.export;

import java.util.Map;
import java.util.UUID;

public interface RestoreBackupUseCase {

    RestoreSummary handle(RestoreBackupCommand command);

    record RestoreBackupCommand(UUID userId, BackupSnapshot snapshot) {
    }

    record RestoreSummary(int schemaVersion, Map<String, Integer> counts) {
    }
}
