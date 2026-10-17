package com.moneylog.core.income.app.usecase;

import com.moneylog.core.income.app.gateways.IncomeProfileGateway;
import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.income.exception.IncomeExceptions.IncomeProfileNotFoundException;
import com.moneylog.core.sk.persistence.Versioned;

public class GetIncomeProfileUseCaseImpl implements GetIncomeProfileUseCase {

    private final IncomeProfileGateway profiles;

    public GetIncomeProfileUseCaseImpl(IncomeProfileGateway profiles) {
        this.profiles = profiles;
    }

    @Override
    public Versioned<IncomeProfile> handle(GetIncomeProfileQuery query) {
        return profiles.findById(query.userId(), query.profileId())
                .orElseThrow(() -> new IncomeProfileNotFoundException(query.profileId()));
    }
}
