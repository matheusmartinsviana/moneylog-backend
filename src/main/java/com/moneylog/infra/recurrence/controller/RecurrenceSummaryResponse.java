package com.moneylog.infra.recurrence.controller;

import com.moneylog.core.sk.money.Money;

public record RecurrenceSummaryResponse(Money fixedCosts, Money recurringIncome, boolean estimate) {
}
