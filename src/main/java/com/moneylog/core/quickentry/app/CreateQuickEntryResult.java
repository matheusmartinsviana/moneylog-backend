package com.moneylog.core.quickentry.app;

import com.moneylog.core.finance.app.transactions.TransactionDetails;

public record CreateQuickEntryResult(TransactionDetails details, String message, boolean created) {
}
