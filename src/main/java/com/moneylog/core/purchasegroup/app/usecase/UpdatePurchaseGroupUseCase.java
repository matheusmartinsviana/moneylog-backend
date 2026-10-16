package com.moneylog.core.purchasegroup.app.usecase;

import java.util.UUID;

import com.moneylog.core.purchasegroup.app.PurchaseGroupView;

public interface UpdatePurchaseGroupUseCase {

    PurchaseGroupView handle(UpdatePurchaseGroupCommand command);

    record UpdatePurchaseGroupCommand(UUID userId, UUID groupId, String name, boolean interest, long version) {
    }
}
