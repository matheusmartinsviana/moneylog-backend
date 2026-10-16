package com.moneylog.infra.recurrence.controller;

import com.moneylog.core.finance.domain.ConsumptionView;
import com.moneylog.core.sk.money.Money;

public record RecurrenceSummaryResponse(ConsumptionView view, Money fixedCosts, Money recurringIncome, boolean estimate) {
}
