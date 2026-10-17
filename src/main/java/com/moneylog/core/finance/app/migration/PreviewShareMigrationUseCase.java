package com.moneylog.core.finance.app.migration;

import java.util.UUID;

import com.moneylog.core.thirdparty.domain.ShareMigrationReport;

public interface PreviewShareMigrationUseCase {

    ShareMigrationReport handle(PreviewShareMigrationQuery query);

    record PreviewShareMigrationQuery(UUID userId) {
    }
}
