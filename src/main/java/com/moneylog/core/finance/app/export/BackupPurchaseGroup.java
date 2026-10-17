package com.moneylog.core.finance.app.export;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;

public record BackupPurchaseGroup(UUID id, UUID userId, String name, boolean interest, Money declaredTotal, List<UUID> members) {

    public BackupPurchaseGroup {
        members = members == null ? List.of() : List.copyOf(members);
    }
}
