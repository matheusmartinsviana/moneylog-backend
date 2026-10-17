package com.moneylog.core.finance.app.export;

import java.util.UUID;

import com.moneylog.core.asset.app.gateways.AssetGateway;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.commitment.app.gateways.CommitmentGateway;
import com.moneylog.core.income.app.gateways.IncomeProfileGateway;
import com.moneylog.core.income.app.gateways.PayrollLoanGateway;
import com.moneylog.core.finance.app.gateways.PurchaseGroupBackupGateway;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.transaction.app.gateways.ForeignChargeGateway;

public class BackupExtrasReader {

    private final CategoryGateway categories;
    private final ForeignChargeGateway foreignCharges;
    private final PurchaseGroupBackupGateway purchaseGroups;
    private final AssetGateway assets;
    private final IncomeProfileGateway incomeProfiles;
    private final PayrollLoanGateway payrollLoans;
    private final CommitmentGateway commitments;
    private final ThirdPartyExpenseGateway thirdParty;

    @SuppressWarnings("java:S107")
    public BackupExtrasReader(CategoryGateway categories, ForeignChargeGateway foreignCharges, PurchaseGroupBackupGateway purchaseGroups,
                              AssetGateway assets, IncomeProfileGateway incomeProfiles, PayrollLoanGateway payrollLoans,
                              CommitmentGateway commitments, ThirdPartyExpenseGateway thirdParty) {
        this.categories = categories;
        this.foreignCharges = foreignCharges;
        this.purchaseGroups = purchaseGroups;
        this.assets = assets;
        this.incomeProfiles = incomeProfiles;
        this.payrollLoans = payrollLoans;
        this.commitments = commitments;
        this.thirdParty = thirdParty;
    }

    public BackupExtras read(UUID userId) {
        return new BackupExtras(categories.systemKeysById(userId), foreignCharges.findAll(userId),
                purchaseGroups.findAll(userId), assets.findAllWithHistory(userId),
                incomeProfiles.findAll(userId), incomeProfiles.occurrenceLinks(userId), payrollLoans.findAllPlans(userId),
                payrollLoans.findAllApplications(userId), commitments.findAll(userId), thirdParty.findAll(userId),
                thirdParty.findAllReceipts(userId));
    }
}
