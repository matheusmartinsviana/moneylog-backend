package com.moneylog.core.finance.app.transactions.handlers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionTypeNotAvailableException;

public final class TransactionHandlers {

    private final Map<Class<?>, TransactionTypeHandler<?>> byCommand = new HashMap<>();

    public TransactionHandlers(List<TransactionTypeHandler<?>> handlers) {
        for (TransactionTypeHandler<?> handler : handlers) {
            if (byCommand.put(handler.commandType(), handler) != null) {
                throw new IllegalArgumentException("dois handlers para " + handler.commandType().getSimpleName());
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <C extends CreateTransactionCommand> TransactionTypeHandler<C> forCommand(C command) {
        TransactionTypeHandler<?> handler = byCommand.get(command.getClass());
        if (handler == null) {
            TransactionType type = command.type();
            throw new TransactionTypeNotAvailableException(type);
        }
        return (TransactionTypeHandler<C>) handler;
    }
}
