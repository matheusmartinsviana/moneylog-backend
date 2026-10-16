package com.moneylog.infra.purchasegroup.gateways;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.app.export.BackupPurchaseGroup;
import com.moneylog.core.finance.app.gateways.PurchaseGroupBackupGateway;
import com.moneylog.core.purchasegroup.app.gateways.PurchaseGroupGateway;
import com.moneylog.core.purchasegroup.domain.PurchaseGroup;
import com.moneylog.core.sk.persistence.Versioned;

public class PurchaseGroupBackupGatewayImpl implements PurchaseGroupBackupGateway {

    private final PurchaseGroupGateway groups;

    public PurchaseGroupBackupGatewayImpl(PurchaseGroupGateway groups) {
        this.groups = groups;
    }

    @Override
    public List<BackupPurchaseGroup> findAll(UUID userId) {
        return groups.findAll(userId).stream().map(Versioned::value)
                .map(g -> new BackupPurchaseGroup(g.id(), g.userId(), g.name(), g.interest(), g.declaredTotal(), g.members())).toList();
    }

    @Override
    public void restore(BackupPurchaseGroup g) {
        groups.insert(PurchaseGroup.restore(g.id(), g.userId(), g.name(), g.interest(), g.declaredTotal(), g.members()));
    }
}
