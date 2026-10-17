package com.moneylog.core.finance.app.transactions.handlers;

import java.util.List;

import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.transaction.domain.Transaction;

public record BuiltTransaction(Transaction transaction, List<Debt> newDebts, List<LedgerEntry> settlementEntries) {

    public BuiltTransaction {
        newDebts = List.copyOf(newDebts);
        settlementEntries = List.copyOf(settlementEntries);
    }

    public BuiltTransaction(Transaction transaction, List<Debt> newDebts) {
        this(transaction, newDebts, List.of());
    }

    public static BuiltTransaction of(Transaction transaction) {
        return new BuiltTransaction(transaction, List.of(), List.of());
    }
}
