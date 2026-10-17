package com.moneylog.infra.income.gateways;

import java.util.List;

import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.income.domain.PayrollAdvance;
import com.moneylog.core.income.domain.PayrollDeduction;
import com.moneylog.core.income.domain.ThirteenthSettings;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.infra.income.persistence.IncomeDeductionEntity;
import com.moneylog.infra.income.persistence.IncomeProfileEntity;

public final class IncomeProfileRepositoryMapper {

    private IncomeProfileRepositoryMapper() {
    }

    public static IncomeProfile toDomain(IncomeProfileEntity e, List<IncomeDeductionEntity> deductions) {
        CurrencyCode currency = CurrencyCode.of(e.getCurrency().strip());
        PayrollAdvance advance = e.getAdvanceDay() == null ? null : new PayrollAdvance(e.getAdvanceDay(), e.getAdvanceBps());
        List<PayrollDeduction> rows = deductions.stream()
                .map(d -> new PayrollDeduction(d.getKind(), d.getLabel(),
                        d.getAmountCents() == null ? null : Money.ofCents(d.getAmountCents(), currency), d.getPercentBps(),
                        d.getStartsOn(), d.getEndsOn(), d.getDebtId(),
                        d.getInterestCents() == null ? null : Money.ofCents(d.getInterestCents(), currency)))
                .toList();
        IncomeProfile profile = IncomeProfile.restore(e.getId(), e.getUserId(), e.getSource(), Money.ofCents(e.getGrossCents(), currency),
                e.getDependents(), advance, e.getBalanceDay(), rows,
                new ThirteenthSettings(e.isThirteenthEnabled(), e.getThirteenthFirstMonth()), e.isArchived());
        profile.defineProvenance(e.provenance());
        return profile;
    }

    public static Versioned<IncomeProfile> toVersioned(IncomeProfileEntity e, List<IncomeDeductionEntity> deductions) {
        return new Versioned<>(toDomain(e, deductions), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }

    public static List<IncomeDeductionEntity> deductionRows(IncomeProfile profile, java.util.function.Supplier<java.util.UUID> ids) {
        List<IncomeDeductionEntity> rows = new java.util.ArrayList<>();
        int position = 1;
        for (PayrollDeduction d : profile.deductions()) {
            rows.add(new IncomeDeductionEntity(ids.get(), profile.userId(), profile.id(), position++, d.kind(), d.label(),
                    d.amount() == null ? null : d.amount().cents(), d.percentBps(), d.startsOn(), d.endsOn(), d.debtId(),
                    d.interestAmount() == null ? null : d.interestAmount().cents()));
        }
        return rows;
    }
}
