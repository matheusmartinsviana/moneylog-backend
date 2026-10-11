package com.moneylog.core.debt.app.usecase;

import java.util.UUID;

import com.moneylog.core.debt.app.DebtView;
import com.moneylog.core.debt.app.gateways.DebtGateway.DebtFilter;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public interface ListDebtsUseCase {

    Slice<DebtView> handle(ListDebtsQuery query);

    record ListDebtsQuery(UUID userId, DebtFilter filter, PageQuery page) {
    }
}
