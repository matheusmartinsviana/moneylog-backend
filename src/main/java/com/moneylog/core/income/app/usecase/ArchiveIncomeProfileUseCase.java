package com.moneylog.core.income.app.usecase;

import java.util.UUID;

import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.sk.persistence.Versioned;

public interface ArchiveIncomeProfileUseCase {

    Versioned<IncomeProfile> handle(ArchiveIncomeProfileCommand command);

    record ArchiveIncomeProfileCommand(UUID userId, UUID profileId) {
    }
}
