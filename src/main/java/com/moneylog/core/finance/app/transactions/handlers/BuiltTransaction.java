package com.moneylog.core.finance.app.transactions.handlers;

import java.util.List;

import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.transaction.domain.Transaction;

/**
 * O que um handler monta: a movimentação e as <b>dívidas novas</b> que nascem com ela (empréstimo, captação, partes da despesa
 * dividida). Quem grava insere as dívidas <b>antes</b> da movimentação (ela aponta para a dívida, ou a dívida para ela por uma
 * FK diferida) e os lançamentos depois.
 */
public record BuiltTransaction(Transaction transaction, List<Debt> newDebts) {

    public BuiltTransaction {
        newDebts = List.copyOf(newDebts);
    }

    public static BuiltTransaction of(Transaction transaction) {
        return new BuiltTransaction(transaction, List.of());
    }
}
