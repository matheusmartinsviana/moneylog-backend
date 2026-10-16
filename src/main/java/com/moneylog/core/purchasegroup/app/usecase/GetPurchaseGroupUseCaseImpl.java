package com.moneylog.core.purchasegroup.app.usecase;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.purchasegroup.app.PurchaseGroupView;
import com.moneylog.core.purchasegroup.app.PurchaseGroupViews;
import com.moneylog.core.purchasegroup.app.gateways.PurchaseGroupGateway;
import com.moneylog.core.purchasegroup.exception.PurchaseGroupExceptions.PurchaseGroupNotFoundException;
import com.moneylog.core.sk.time.TimeProvider;

public class GetPurchaseGroupUseCaseImpl implements GetPurchaseGroupUseCase {

    private final PurchaseGroupGateway groups;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public GetPurchaseGroupUseCaseImpl(PurchaseGroupGateway groups, FinancialRecordsGateway records, TimeProvider time) {
        this.groups = groups;
        this.records = records;
        this.time = time;
    }

    @Override
    public PurchaseGroupView handle(GetPurchaseGroupQuery query) {
        return PurchaseGroupViews.of(groups.findById(query.userId(), query.groupId())
                .orElseThrow(() -> new PurchaseGroupNotFoundException(query.groupId())), records.load(query.userId()), time.today());
    }
}
