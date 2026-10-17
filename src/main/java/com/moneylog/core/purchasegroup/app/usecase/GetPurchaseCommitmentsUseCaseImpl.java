package com.moneylog.core.purchasegroup.app.usecase;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.purchasegroup.app.PurchaseCommitments;
import com.moneylog.core.sk.time.TimeProvider;

public class GetPurchaseCommitmentsUseCaseImpl implements GetPurchaseCommitmentsUseCase {

    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public GetPurchaseCommitmentsUseCaseImpl(FinancialRecordsGateway records, TimeProvider time) {
        this.records = records;
        this.time = time;
    }

    @Override
    public PurchaseCommitments handle(GetPurchaseCommitmentsQuery query) {
        return PurchaseCommitments.of(records.load(query.userId()), query.until(), time.today());
    }
}
