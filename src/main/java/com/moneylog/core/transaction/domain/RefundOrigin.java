package com.moneylog.core.transaction.domain;

import java.util.Objects;

import com.moneylog.core.sk.money.Money;

public record RefundOrigin(Transaction original, Money alreadyRefunded) {

    public RefundOrigin {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(alreadyRefunded, "alreadyRefunded");
    }

    public static RefundOrigin firstRefundOf(Transaction original) {
        return new RefundOrigin(original, Money.zero(original.amount().currency()));
    }
}
