package com.moneylog.core.finance.app.migration;

import java.util.UUID;

public interface ApplyShareMigrationUseCase {

    Result handle(ApplyShareMigrationCommand command);

    record ApplyShareMigrationCommand(UUID userId, String reportHash, boolean backupConfirmed) {
    }

    record Result(String reportHash, boolean alreadyApplied, int migratedPurchases, int refundsNeutralized, int legacyReceiptsLedgered) {
    }
}
