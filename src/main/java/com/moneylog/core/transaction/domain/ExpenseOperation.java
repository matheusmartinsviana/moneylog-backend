package com.moneylog.core.transaction.domain;

import java.util.List;

import com.moneylog.core.debt.domain.Debt;

public record ExpenseOperation(Transaction transaction, List<Debt> debts) {

    public ExpenseOperation {
        debts = List.copyOf(debts);
    }
}
