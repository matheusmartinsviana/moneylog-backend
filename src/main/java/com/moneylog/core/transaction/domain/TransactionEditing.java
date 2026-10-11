package com.moneylog.core.transaction.domain;

import java.util.Objects;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.exception.TransactionExceptions.RefundCategoryLockedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.RefundExceedsOriginalException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotEditableException;

public final class TransactionEditing {

    private TransactionEditing() {
    }

    public static Transaction replace(Transaction current, Transaction replacement, Money activeRefunds) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(replacement, "replacement");
        if (!current.isActive()) {
            throw new TransactionNotEditableException("ela está estornada.");
        }
        if (!current.id().equals(replacement.id()) || !current.userId().equals(replacement.userId())
                || current.type() != replacement.type()) {
            throw new TransactionNotEditableException("o tipo, o id e o dono não mudam na edição.");
        }
        if (current.type() == TransactionType.EXPENSE && activeRefunds != null && activeRefunds.isPositive()) {
            if (!Objects.equals(current.categoryId(), replacement.categoryId())) {
                throw new RefundCategoryLockedException();
            }
            if (replacement.amount().isLessThan(activeRefunds)) {
                throw new RefundExceedsOriginalException();
            }
        }
        replacement.inheritVersion(current);
        return replacement;
    }
}
