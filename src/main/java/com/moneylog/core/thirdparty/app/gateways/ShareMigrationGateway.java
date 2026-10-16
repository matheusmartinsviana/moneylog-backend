package com.moneylog.core.thirdparty.app.gateways;

import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.thirdparty.domain.ShareMigrationReport;
import com.moneylog.core.thirdparty.domain.ThirdPartyExpense;
import com.moneylog.core.transaction.domain.SplitMode;

public interface ShareMigrationGateway {

    record MigratedMark(UUID id, ThirdPartyExpense mark, SplitMode mode, Money third, Money refunded, Money legacyReceived,
                        String reportHash, Instant migratedAt) {
    }

    void archive(MigratedMark migrated);

    boolean existsApplied(UUID userId, String reportHash);

    void saveReport(UUID userId, ShareMigrationReport report);
}
