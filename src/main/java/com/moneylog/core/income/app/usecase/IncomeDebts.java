package com.moneylog.core.income.app.usecase;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.exception.DebtExceptions.DebtNotFoundException;
import com.moneylog.core.income.app.IncomeProfileSpec;
import com.moneylog.core.income.app.gateways.IncomeProfileGateway;
import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.income.domain.PayrollDeduction;
import com.moneylog.core.sk.persistence.Versioned;

final class IncomeDebts {

    private IncomeDebts() {
    }

    static java.util.Set<UUID> debtIdsOf(IncomeProfile profile) {
        return profile.deductions().stream().map(PayrollDeduction::debtId).filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
    }

    static void releasePayrollDebts(DebtGateway debts, IncomeProfileGateway profiles, UUID userId, IncomeProfile profile,
                                    Collection<UUID> candidates) {
        Set<UUID> stillLinked = new HashSet<>();
        for (IncomeProfile other : profiles.activeProfiles(userId)) {
            if (!other.id().equals(profile.id())) {
                stillLinked.addAll(debtIdsOf(other));
            }
        }
        if (!profile.isArchived()) {
            stillLinked.addAll(debtIdsOf(profile));
        }
        List<UUID> released = candidates.stream().filter(id -> !stillLinked.contains(id)).distinct().sorted().toList();
        released.forEach(debtId -> debts.lockForUpdate(userId, debtId));
        for (UUID debtId : released) {
            debts.findById(userId, debtId).ifPresent(found -> {
                if (found.value().unmarkPayroll()) {
                    debts.update(found.value(), found.version());
                }
            });
        }
    }

    static void linkPayrollDebts(DebtGateway debts, UUID userId, IncomeProfileSpec spec) {
        spec.deductions().stream().map(PayrollDeduction::debtId).filter(Objects::nonNull).distinct().sorted()
                .forEach(debtId -> debts.lockForUpdate(userId, debtId));
        for (UUID debtId : spec.deductions().stream().map(PayrollDeduction::debtId).filter(Objects::nonNull).distinct().toList()) {
            Versioned<Debt> found = debts.findById(userId, debtId)
                    .filter(candidate -> candidate.value().direction() == DebtDirection.PAYABLE)
                    .orElseThrow(() -> new DebtNotFoundException(debtId));
            if (found.value().markPayroll()) {
                debts.update(found.value(), found.version());
            }
        }
    }
}
