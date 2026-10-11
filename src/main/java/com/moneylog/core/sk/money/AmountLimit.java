package com.moneylog.core.sk.money;

import java.util.Objects;

import com.moneylog.core.sk.money.MoneyExceptions.AmountAboveLimitException;

public final class AmountLimit {

    public static final long MAX_CENTS = 99_999_999_999L;

    private AmountLimit() {
    }

    public static Money max(CurrencyCode currency) {
        return Money.ofCents(MAX_CENTS, currency);
    }

    public static Money ensureWithinLimit(Money amount, String field) {
        Objects.requireNonNull(amount, "amount");
        if (amount.cents() > MAX_CENTS || amount.cents() < -MAX_CENTS) {
            throw new AmountAboveLimitException(field, max(amount.currency()));
        }
        return amount;
    }
}
