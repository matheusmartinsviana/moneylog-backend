package com.moneylog.core.sk.ledger;

public enum BookType {
    ACCOUNT(true),
    CARD(true),
    RECEIVABLE(true),
    PAYABLE(true),
    EXPENSE(true),
    INCOME(true),
    EQUITY(false);

    private final boolean requiresId;

    BookType(boolean requiresId) {
        this.requiresId = requiresId;
    }

    public boolean requiresId() {
        return requiresId;
    }

    public boolean isNetWorth() {
        return this == ACCOUNT || this == CARD || this == RECEIVABLE || this == PAYABLE;
    }

    public boolean isResult() {
        return this == EXPENSE || this == INCOME;
    }
}
