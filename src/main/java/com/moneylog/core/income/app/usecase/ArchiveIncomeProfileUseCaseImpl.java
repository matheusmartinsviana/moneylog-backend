package com.moneylog.core.income.app.usecase;

import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.income.app.gateways.IncomeProfileGateway;
import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.income.exception.IncomeExceptions.IncomeProfileNotFoundException;
import com.moneylog.core.sk.persistence.Versioned;

public class ArchiveIncomeProfileUseCaseImpl implements ArchiveIncomeProfileUseCase {

    private final IncomeProfileGateway profiles;
    private final DebtGateway debts;

    public ArchiveIncomeProfileUseCaseImpl(IncomeProfileGateway profiles, DebtGateway debts) {
        this.profiles = profiles;
        this.debts = debts;
    }

    @Override
    public Versioned<IncomeProfile> handle(ArchiveIncomeProfileCommand command) {
        profiles.lockForUpdate(command.userId(), command.profileId());
        Versioned<IncomeProfile> current = profiles.findById(command.userId(), command.profileId())
                .orElseThrow(() -> new IncomeProfileNotFoundException(command.profileId()));
        IncomeProfile profile = current.value();
        profile.archive();
        Versioned<IncomeProfile> archived = profiles.update(profile, current.version());
        IncomeDebts.releasePayrollDebts(debts, profiles, command.userId(), profile, IncomeDebts.debtIdsOf(profile));
        return archived;
    }
}
