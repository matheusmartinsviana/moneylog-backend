package com.moneylog.core.income.app.usecase;

import java.util.UUID;

import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.sk.persistence.Versioned;

public interface GetIncomeProfileUseCase {

    Versioned<IncomeProfile> handle(GetIncomeProfileQuery query);

    record GetIncomeProfileQuery(UUID userId, UUID profileId) {
    }
}
