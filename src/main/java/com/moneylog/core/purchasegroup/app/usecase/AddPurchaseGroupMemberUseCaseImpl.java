package com.moneylog.core.purchasegroup.app.usecase;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.purchasegroup.app.PurchaseGroupView;
import com.moneylog.core.purchasegroup.app.PurchaseGroupViews;
import com.moneylog.core.purchasegroup.app.PurchasePlan;
import com.moneylog.core.purchasegroup.app.gateways.PurchaseGroupGateway;
import com.moneylog.core.purchasegroup.domain.PurchaseGroup;
import com.moneylog.core.purchasegroup.exception.PurchaseGroupExceptions.PurchaseGroupMemberInvalidException;
import com.moneylog.core.purchasegroup.exception.PurchaseGroupExceptions.PurchaseGroupNotFoundException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;

public class AddPurchaseGroupMemberUseCaseImpl implements AddPurchaseGroupMemberUseCase {

    private final PurchaseGroupGateway groups;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public AddPurchaseGroupMemberUseCaseImpl(PurchaseGroupGateway groups, FinancialRecordsGateway records, TimeProvider time) {
        this.groups = groups;
        this.records = records;
        this.time = time;
    }

    @Override
    public PurchaseGroupView handle(AddPurchaseGroupMemberCommand command) {
        groups.lockForUpdate(command.userId(), command.groupId());
        Versioned<PurchaseGroup> current = groups.findById(command.userId(), command.groupId())
                .orElseThrow(() -> new PurchaseGroupNotFoundException(command.groupId()));
        FinancialRecords loaded = records.load(command.userId());
        Transaction purchase = loaded.transaction(command.transactionId())
                .orElseThrow(() -> new TransactionNotFoundException(command.transactionId()));
        if (!PurchasePlan.isInstallmentPurchase(purchase)) {
            throw new PurchaseGroupMemberInvalidException("só uma compra parcelada de cartão e ativa entra no grupo.");
        }
        if (groups.groupIdOfTransaction(command.userId(), command.transactionId()).isPresent()) {
            throw new PurchaseGroupMemberInvalidException("a compra já pertence a um grupo.");
        }
        PurchaseGroup group = current.value();
        group.addMember(command.transactionId());
        return PurchaseGroupViews.of(groups.update(group, current.version()), loaded, time.today());
    }
}
