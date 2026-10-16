package com.moneylog.core.finance.app.export;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.commitment.domain.TrackedCommitment;
import com.moneylog.core.income.app.PayrollLoanApplication;
import com.moneylog.core.income.app.PayrollLoanPlanLink;
import com.moneylog.core.income.app.gateways.IncomeProfileGateway.OccurrenceLink;
import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.thirdparty.domain.ThirdPartyExpense;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;
import com.moneylog.core.transaction.domain.ForeignCharge;

public record BackupExtras(Map<UUID, String> categorySystemKeys, Map<UUID, ForeignCharge> foreignCharges,
                           List<BackupPurchaseGroup> purchaseGroups, List<Asset> assets, List<IncomeProfile> incomeProfiles,
                           List<OccurrenceLink> incomeOccurrences, List<PayrollLoanPlanLink> payrollLoanPlans,
                           List<PayrollLoanApplication> payrollLoanApplications, List<TrackedCommitment> commitments,
                           List<ThirdPartyExpense> thirdPartyExpenses, List<ThirdPartyReceipt> thirdPartyReceipts) {

    public static final BackupExtras EMPTY = new BackupExtras(Map.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of());

    public BackupExtras {
        categorySystemKeys = categorySystemKeys == null ? Map.of() : Map.copyOf(categorySystemKeys);
        foreignCharges = foreignCharges == null ? Map.of() : Map.copyOf(foreignCharges);
        purchaseGroups = orEmpty(purchaseGroups);
        assets = orEmpty(assets);
        incomeProfiles = orEmpty(incomeProfiles);
        incomeOccurrences = orEmpty(incomeOccurrences);
        payrollLoanPlans = orEmpty(payrollLoanPlans);
        payrollLoanApplications = orEmpty(payrollLoanApplications);
        commitments = orEmpty(commitments);
        thirdPartyExpenses = orEmpty(thirdPartyExpenses);
        thirdPartyReceipts = orEmpty(thirdPartyReceipts);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}
