package com.moneylog.core.quickentry.app.gateways;

import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;

public interface QuickEntryUsageGateway {

    record Usage(long count, Money expenseTotal, Money incomeTotal) {
    }

    void lockUser(UUID userId);

    Usage activeUsage(UUID userId, Instant fromInclusive, Instant toExclusive);
}
