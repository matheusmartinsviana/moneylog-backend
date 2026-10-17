package com.moneylog.core.income.app.usecase;

import java.util.UUID;

import com.moneylog.core.income.app.IncomeProfileSpec;
import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.sk.persistence.Versioned;

public interface CreateIncomeProfileUseCase {

    Versioned<IncomeProfile> handle(CreateIncomeProfileCommand command);

    String IDEMPOTENCY_SCOPE = "POST /v1/income-profiles";

    record CreateIncomeProfileCommand(UUID userId, IncomeProfileSpec spec, UUID idempotencyKey) {
    }
}
