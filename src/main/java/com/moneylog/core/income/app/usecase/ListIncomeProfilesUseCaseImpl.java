package com.moneylog.core.income.app.usecase;

import com.moneylog.core.income.app.gateways.IncomeProfileGateway;
import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;

public class ListIncomeProfilesUseCaseImpl implements ListIncomeProfilesUseCase {

    private final IncomeProfileGateway profiles;

    public ListIncomeProfilesUseCaseImpl(IncomeProfileGateway profiles) {
        this.profiles = profiles;
    }

    @Override
    public Slice<Versioned<IncomeProfile>> handle(ListIncomeProfilesQuery query) {
        return profiles.list(query.userId(), query.archived(), query.page());
    }
}
