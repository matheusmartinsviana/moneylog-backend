package com.moneylog.infra.recurrence.controller;

import com.moneylog.core.sk.money.Money;

/** {@code RecurrenceSummary} do contrato: custo fixo e receita recorrente <b>estimados</b> por mês (S-146). */
public record RecurrenceSummaryResponse(Money fixedCosts, Money recurringIncome, boolean estimate) {
}
