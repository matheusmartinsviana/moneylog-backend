package com.moneylog.core.finance.app.export;

import java.util.UUID;

public interface ExportBackupUseCase {

    int MAX_TRANSACTIONS = 200_000;

    BackupSnapshot handle(ExportBackupQuery query);

    record ExportBackupQuery(UUID userId) {
    }
}
