package com.moneylog.core.transaction.domain;

import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNatureInvalidException;

public enum TransactionNature {
    FIXED,
    VARIABLE;

    public static TransactionNature parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.strip());
        } catch (IllegalArgumentException e) {
            throw new TransactionNatureInvalidException("use FIXED ou VARIABLE.");
        }
    }

    public static TransactionNature forExpense(TransactionNature nature) {
        return nature == null ? VARIABLE : nature;
    }
}
