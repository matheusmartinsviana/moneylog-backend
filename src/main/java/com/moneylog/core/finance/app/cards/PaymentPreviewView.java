package com.moneylog.core.finance.app.cards;

import java.util.Objects;

import com.moneylog.core.sk.money.Money;

public record PaymentPreviewView(Kind kind, Money outstandingAfter, Money creditCarryAfter) {

    public enum Kind {
        FULL,
        PARTIAL,
        OVERPAY,
        PREPAY
    }

    public PaymentPreviewView {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(outstandingAfter, "outstandingAfter");
        Objects.requireNonNull(creditCarryAfter, "creditCarryAfter");
    }
}
