package com.moneylog.core.transaction.app.query;

import java.util.UUID;
import java.util.function.Predicate;

import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;

public record EntryFilter(UUID categoryId, UUID accountId, PaymentMethod method) {

    public Predicate<UUID> debtCategoryPredicate() {
        return debtCategory -> accountId == null && method == null && (categoryId == null || categoryId.equals(debtCategory));
    }

    public Predicate<Transaction> predicate() {
        return t -> (categoryId == null || categoryId.equals(t.categoryId()))
                && (accountId == null || accountId.equals(t.accountId()))
                && (method == null || t.paymentMethod() == method);
    }
}
