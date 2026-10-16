package com.moneylog.core.finance.app.networth.usecase;

import java.util.UUID;

import com.moneylog.core.finance.domain.NetWorthOverview;
import com.moneylog.core.sk.time.Competence;

public interface GetNetWorthOverviewUseCase {

    NetWorthOverview handle(GetNetWorthOverviewQuery query);

    record GetNetWorthOverviewQuery(UUID userId, Competence month, Integer targetMonths) {
    }
}
