package com.moneylog.core.purchasegroup.app.usecase;

import java.util.UUID;

import com.moneylog.core.purchasegroup.app.PurchaseGroupView;

public interface AddPurchaseGroupMemberUseCase {

    PurchaseGroupView handle(AddPurchaseGroupMemberCommand command);

    record AddPurchaseGroupMemberCommand(UUID userId, UUID groupId, UUID transactionId) {
    }
}
