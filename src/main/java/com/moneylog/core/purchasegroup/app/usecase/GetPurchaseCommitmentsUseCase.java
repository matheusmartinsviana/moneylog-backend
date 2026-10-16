package com.moneylog.core.purchasegroup.app.usecase;

import java.util.UUID;

import com.moneylog.core.purchasegroup.app.PurchaseCommitments;
import com.moneylog.core.sk.time.Competence;

public interface GetPurchaseCommitmentsUseCase {

    PurchaseCommitments handle(GetPurchaseCommitmentsQuery query);

    record GetPurchaseCommitmentsQuery(UUID userId, Competence until) {
    }
}
