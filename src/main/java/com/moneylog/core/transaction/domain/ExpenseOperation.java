package com.moneylog.core.transaction.domain;

import java.util.List;

import com.moneylog.core.debt.domain.Debt;

/** Despesa dividida: a despesa e as dívidas que nascem dela (uma por parte), gravadas juntas. */
public record ExpenseOperation(Transaction transaction, List<Debt> debts) {

    public ExpenseOperation {
        debts = List.copyOf(debts);
    }
}
