package com.moneylog.core.transaction.app.query;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionSource;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionType;

public record TransactionFilter(LocalDate from, LocalDate to, TransactionType type, UUID accountId, UUID categoryId,
                                PaymentMethod method, TransactionStatus status, String q,
                                TransactionSource source) {

    public static final int Q_MAX_LENGTH = 100;

    public TransactionFilter(LocalDate from, LocalDate to, TransactionType type, UUID accountId, UUID categoryId,
                             PaymentMethod method, TransactionStatus status, String q) {
        this(from, to, type, accountId, categoryId, method, status, q, null);
    }

    public TransactionFilter {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from depois de to");
        }
        status = status == null ? TransactionStatus.ACTIVE : status;
        q = q == null || q.isBlank() ? null : q.strip();
    }

    public boolean matches(Transaction transaction) {
        return inPeriod(transaction.date()) && attributes().test(transaction);
    }

    public Predicate<Transaction> attributes() {
        String needle = q == null ? null : q.toLowerCase(Locale.ROOT);
        return t -> t.status() == status
                && (type == null || t.type() == type)
                && (accountId == null || accountId.equals(t.accountId()) || accountId.equals(t.counterpartAccountId()))
                && (categoryId == null || categoryId.equals(t.categoryId()))
                && (source == null || t.origin().source() == source)
                && (method == null || t.paymentMethod() == method)
                && (needle == null || (t.description() != null && t.description().toLowerCase(Locale.ROOT).contains(needle)));
    }

    public boolean inPeriod(LocalDate date) {
        return (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
    }
}
