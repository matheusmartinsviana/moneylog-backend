package com.moneylog.core.purchasegroup.app.usecase;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.purchasegroup.app.PurchaseGroupView;
import com.moneylog.core.purchasegroup.app.PurchaseGroupViews;
import com.moneylog.core.purchasegroup.app.gateways.PurchaseGroupGateway;
import com.moneylog.core.purchasegroup.domain.PurchaseGroup;
import com.moneylog.core.purchasegroup.exception.PurchaseGroupExceptions.PurchaseGroupNotFoundException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class UpdatePurchaseGroupUseCaseImpl implements UpdatePurchaseGroupUseCase {

    private final PurchaseGroupGateway groups;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public UpdatePurchaseGroupUseCaseImpl(PurchaseGroupGateway groups, FinancialRecordsGateway records, TimeProvider time) {
        this.groups = groups;
        this.records = records;
        this.time = time;
    }

    @Override
    public PurchaseGroupView handle(UpdatePurchaseGroupCommand command) {
        groups.lockForUpdate(command.userId(), command.groupId());
        Versioned<PurchaseGroup> current = groups.findById(command.userId(), command.groupId())
                .orElseThrow(() -> new PurchaseGroupNotFoundException(command.groupId()));
        if (current.version() != command.version()) {
            throw new VersionConflictException(command.version(), current.version());
        }
        PurchaseGroup group = current.value();
        group.revise(command.name(), command.interest());
        return PurchaseGroupViews.of(groups.update(group, command.version()), records.load(command.userId()), time.today());
    }
}
