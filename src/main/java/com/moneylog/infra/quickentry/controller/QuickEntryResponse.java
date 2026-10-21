package com.moneylog.infra.quickentry.controller;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.quickentry.app.CreateQuickEntryResult;
import com.moneylog.core.quickentry.domain.QuickEntryKind;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionSource;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionType;

public record QuickEntryResponse(UUID id, QuickEntryKind type, TransactionStatus status, Money amount, LocalDate date,
                                 String description, Ref category, Ref account, PaymentMethod method, TransactionSource source,
                                 String message) {

    public record Ref(UUID id, String name) {
    }

    public static QuickEntryResponse of(CreateQuickEntryResult result) {
        Transaction transaction = result.details().transaction();
        if (result.details().category() == null) {
            throw new IllegalStateException("lançamento rápido sem categoria aplicada");
        }
        QuickEntryKind kind = transaction.type() == TransactionType.INCOME ? QuickEntryKind.INCOME : QuickEntryKind.EXPENSE;
        return new QuickEntryResponse(transaction.id(), kind, transaction.status(), transaction.amount(), transaction.date(),
                transaction.description(), new Ref(result.details().category().id(), result.details().category().name()),
                new Ref(result.details().account().id(), result.details().account().name()), transaction.paymentMethod(),
                transaction.origin().source(), result.message());
    }
}
