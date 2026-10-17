package com.moneylog.core.purchasegroup.app.usecase;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.purchasegroup.app.PurchaseGroupView;
import com.moneylog.core.purchasegroup.app.PurchaseGroupViews;
import com.moneylog.core.purchasegroup.app.gateways.PurchaseGroupGateway;
import com.moneylog.core.purchasegroup.domain.PurchaseGroup;
import com.moneylog.core.purchasegroup.exception.PurchaseGroupExceptions.PurchaseGroupNotFoundException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class RemovePurchaseGroupMemberUseCaseImpl implements RemovePurchaseGroupMemberUseCase {

    private final PurchaseGroupGateway groups;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public RemovePurchaseGroupMemberUseCaseImpl(PurchaseGroupGateway groups, FinancialRecordsGateway records, TimeProvider time) {
        this.groups = groups;
        this.records = records;
        this.time = time;
    }

    @Override
    public PurchaseGroupView handle(RemovePurchaseGroupMemberCommand command) {
        groups.lockForUpdate(command.userId(), command.groupId());
        Versioned<PurchaseGroup> current = groups.findById(command.userId(), command.groupId())
                .orElseThrow(() -> new PurchaseGroupNotFoundException(command.groupId()));
        PurchaseGroup group = current.value();
        group.removeMember(command.transactionId());
        return PurchaseGroupViews.of(groups.update(group, current.version()), records.load(command.userId()), time.today());
    }
}
