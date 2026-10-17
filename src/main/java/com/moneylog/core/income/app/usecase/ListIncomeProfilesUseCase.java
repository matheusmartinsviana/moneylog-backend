package com.moneylog.core.income.app.usecase;

import java.util.UUID;

import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;

public interface ListIncomeProfilesUseCase {

    Slice<Versioned<IncomeProfile>> handle(ListIncomeProfilesQuery query);

    record ListIncomeProfilesQuery(UUID userId, boolean archived, PageQuery page) {
    }
}
