package com.moneylog.core.finance.app.transactions.handlers;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionOrigin;

public interface TransactionTypeHandler<C extends CreateTransactionCommand> {

    Class<C> commandType();

    default List<UUID> transactionsToLock(C command) {
        return List.of();
    }

    default UUID cardToLock(C command) {
        return null;
    }

    default List<UUID> debtsToLock(C command) {
        return List.of();
    }

    default List<UUID> personsToLock(C command) {
        return List.of();
    }

    default List<UUID> accountsToLock(C command) {
        return List.of();
    }

    Transaction build(C command, UUID id, TransactionOrigin origin, Transaction editing);

    default BuiltTransaction buildWithEffects(C command, UUID id, TransactionOrigin origin, Transaction editing) {
        return BuiltTransaction.of(build(command, id, origin, editing));
    }
}
