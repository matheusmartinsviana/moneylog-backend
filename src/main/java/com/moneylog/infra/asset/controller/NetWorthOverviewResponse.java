package com.moneylog.infra.asset.controller;

import com.moneylog.core.finance.domain.NetWorthOverview;
import com.moneylog.core.sk.money.Money;

public record NetWorthOverviewResponse(Money availableCash, Money availableAssets, Money reservedAssets, Money receivablesPending,
                                       Money debts, EmergencyReserveResponse emergencyReserve) {

    public record EmergencyReserveResponse(Money amount, Money monthlyBase, String coverageMonths, Integer targetMonths,
                                           Money targetAmount, Money missingToTarget) {
    }

    public static NetWorthOverviewResponse of(NetWorthOverview o) {
        NetWorthOverview.EmergencyReserve r = o.emergencyReserve();
        return new NetWorthOverviewResponse(o.availableCash(), o.availableAssets(), o.reservedAssets(), o.receivablesPending(), o.debts(),
                new EmergencyReserveResponse(r.amount(), r.monthlyBase(), r.coverageMonths() == null ? null : r.coverageMonths().toPlainString(),
                        r.targetMonths(), r.targetAmount(), r.missingToTarget()));
    }
}
