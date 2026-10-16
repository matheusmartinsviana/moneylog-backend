package com.moneylog.core.income.app.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.income.app.GeneratedIncomeOccurrence;
import com.moneylog.core.sk.time.Competence;

public interface GenerateIncomeOccurrencesUseCase {

    List<GeneratedIncomeOccurrence> handle(GenerateIncomeOccurrencesCommand command);

    int MAX_MONTHS = 12;

    record GenerateIncomeOccurrencesCommand(UUID userId, UUID profileId, Competence from, Competence to) {
    }
}
