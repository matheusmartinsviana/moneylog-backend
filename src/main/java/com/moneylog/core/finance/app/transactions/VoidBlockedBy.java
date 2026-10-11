package com.moneylog.core.finance.app.transactions;

public enum VoidBlockedBy {
    ACCOUNT_ARCHIVED,
    HAS_ACTIVE_REFUNDS,
    TRANSACTION_IN_CLOSED_INVOICE,
    DEBT_HAS_SETTLEMENTS,
    DEBT_CLOSED
}
