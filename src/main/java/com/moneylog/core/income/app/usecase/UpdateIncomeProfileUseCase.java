package com.moneylog.core.income.app.usecase;

import java.util.UUID;

import com.moneylog.core.income.app.IncomeProfileSpec;
import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.sk.persistence.Versioned;

public interface UpdateIncomeProfileUseCase {

    Versioned<IncomeProfile> handle(UpdateIncomeProfileCommand command);

    record UpdateIncomeProfileCommand(UUID userId, UUID profileId, IncomeProfileSpec spec, long version) {
    }
}
