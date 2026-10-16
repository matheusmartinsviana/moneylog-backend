package com.moneylog.core.income.app.usecase;

import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.income.app.IncomeProfileSpec;
import com.moneylog.core.income.app.gateways.IncomeProfileGateway;
import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.income.exception.IncomeExceptions.IncomeProfileNotFoundException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.persistence.Versioned;

public class UpdateIncomeProfileUseCaseImpl implements UpdateIncomeProfileUseCase {

    private final IncomeProfileGateway profiles;
    private final DebtGateway debts;

    public UpdateIncomeProfileUseCaseImpl(IncomeProfileGateway profiles, DebtGateway debts) {
        this.profiles = profiles;
        this.debts = debts;
    }

    @Override
    public Versioned<IncomeProfile> handle(UpdateIncomeProfileCommand command) {
        profiles.lockForUpdate(command.userId(), command.profileId());
        Versioned<IncomeProfile> current = profiles.findById(command.userId(), command.profileId())
                .orElseThrow(() -> new IncomeProfileNotFoundException(command.profileId()));
        if (current.version() != command.version()) {
            throw new VersionConflictException(command.version(), current.version());
        }
        IncomeProfileSpec spec = command.spec();
        IncomeProfile profile = current.value();
        java.util.Set<java.util.UUID> previousDebts = IncomeDebts.debtIdsOf(profile);
        profile.revise(spec.source(), spec.gross(), spec.dependents(), spec.advance(), spec.balanceDayOfMonth(), spec.deductions(),
                spec.thirteenth());
        if (spec.provenance() != null) {
            profile.defineProvenance(spec.provenance());
        }
        IncomeDebts.linkPayrollDebts(debts, command.userId(), spec);
        Versioned<IncomeProfile> updated = profiles.update(profile, command.version());
        IncomeDebts.releasePayrollDebts(debts, profiles, command.userId(), profile, previousDebts);
        return updated;
    }
}
