package com.moneylog.core.finance.app.transactions.handlers;

import java.util.List;

import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.transaction.domain.Transaction;

public record BuiltTransaction(Transaction transaction, List<Debt> newDebts) {

    public BuiltTransaction {
        newDebts = List.copyOf(newDebts);
    }

    public static BuiltTransaction of(Transaction transaction) {
        return new BuiltTransaction(transaction, List.of());
    }
}
