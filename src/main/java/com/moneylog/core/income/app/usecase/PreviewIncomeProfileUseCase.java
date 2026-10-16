package com.moneylog.core.income.app.usecase;

import java.util.UUID;

import com.moneylog.core.income.domain.IncomePreview;
import com.moneylog.core.sk.time.Competence;

public interface PreviewIncomeProfileUseCase {

    IncomePreview handle(PreviewIncomeProfileQuery query);

    record PreviewIncomeProfileQuery(UUID userId, UUID profileId, Competence month) {
    }
}
