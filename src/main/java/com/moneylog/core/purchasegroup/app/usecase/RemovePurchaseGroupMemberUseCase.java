package com.moneylog.core.purchasegroup.app.usecase;

import java.util.UUID;

import com.moneylog.core.purchasegroup.app.PurchaseGroupView;

public interface RemovePurchaseGroupMemberUseCase {

    PurchaseGroupView handle(RemovePurchaseGroupMemberCommand command);

    record RemovePurchaseGroupMemberCommand(UUID userId, UUID groupId, UUID transactionId) {
    }
}
