package com.moneylog.core.transaction.domain;

import com.moneylog.core.debt.domain.Debt;

public record DebtOperation(Transaction transaction, Debt debt) {
}
