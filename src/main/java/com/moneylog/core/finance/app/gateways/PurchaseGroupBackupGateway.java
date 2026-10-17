package com.moneylog.core.finance.app.gateways;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.app.export.BackupPurchaseGroup;

public interface PurchaseGroupBackupGateway {

    List<BackupPurchaseGroup> findAll(UUID userId);

    void restore(BackupPurchaseGroup group);
}
