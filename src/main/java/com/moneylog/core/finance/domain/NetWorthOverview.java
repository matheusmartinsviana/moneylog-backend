package com.moneylog.core.finance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public record NetWorthOverview(Money availableCash, Money availableAssets, Money reservedAssets, Money receivablesPending, Money debts,
                               EmergencyReserve emergencyReserve) {

    public static final int MIN_TARGET_MONTHS = 1;
    public static final int MAX_TARGET_MONTHS = 60;
    public static final int COVERAGE_SCALE = 1;

    public record EmergencyReserve(Money amount, Money monthlyBase, BigDecimal coverageMonths, Integer targetMonths, Money targetAmount,
                                   Money missingToTarget) {
    }

    public static NetWorthOverview of(Money availableCash, Collection<Asset> assets, Money receivablesPending, Money debts,
                                      Money monthlyBase, Integer targetMonths) {
        Money zero = Money.zero(CurrencyCode.BRL);
        Money available = zero;
        Money reserved = zero;
        Money reserve = zero;
        for (Asset asset : assets) {
            if (asset.isArchived()) {
                continue;
            }
            if (asset.isReserved()) {
                reserved = reserved.add(asset.balance());
            } else {
                available = available.add(asset.balance());
            }
            if (asset.isEmergencyReserve()) {
                reserve = reserve.add(asset.balance());
            }
        }
        return new NetWorthOverview(availableCash, available, reserved, receivablesPending, debts,
                reserveOf(reserve, monthlyBase, targetMonths));
    }

    static EmergencyReserve reserveOf(Money amount, Money monthlyBase, Integer targetMonths) {
        BigDecimal coverage = monthlyBase.isPositive()
                ? BigDecimal.valueOf(amount.cents()).divide(BigDecimal.valueOf(monthlyBase.cents()), COVERAGE_SCALE, RoundingMode.DOWN)
                : null;
        if (targetMonths == null) {
            return new EmergencyReserve(amount, monthlyBase, coverage, null, null, null);
        }
        Money target = monthlyBase.times(targetMonths);
        Money missing = amount.isLessThan(target) ? target.subtract(amount) : Money.zero(CurrencyCode.BRL);
        return new EmergencyReserve(amount, monthlyBase, coverage, targetMonths, target, missing);
    }
}
