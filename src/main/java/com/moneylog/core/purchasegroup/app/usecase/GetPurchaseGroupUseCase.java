package com.moneylog.core.purchasegroup.app.usecase;

import java.util.UUID;

import com.moneylog.core.purchasegroup.app.PurchaseGroupView;

public interface GetPurchaseGroupUseCase {

    PurchaseGroupView handle(GetPurchaseGroupQuery query);

    record GetPurchaseGroupQuery(UUID userId, UUID groupId) {
    }
}
