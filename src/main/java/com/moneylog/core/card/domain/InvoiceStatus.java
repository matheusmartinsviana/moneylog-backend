package com.moneylog.core.card.domain;

public enum InvoiceStatus {
    FUTURE,
    OPEN,
    CLOSED,
    OVERDUE,
    PAID;

    public boolean isClosed() {
        return this == CLOSED || this == OVERDUE || this == PAID;
    }
}
