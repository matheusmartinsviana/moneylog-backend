package com.moneylog.infra.quickentry.controller;

import java.util.Map;
import java.util.TreeMap;

import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;

final class QuickEntryAlertParams {

    static final int DESCRIPTION_MAX = 80;

    private QuickEntryAlertParams() {
    }

    static Map<String, String> of(TransactionDetails details) {
        Transaction transaction = details.transaction();
        Map<String, String> params = new TreeMap<>();
        params.put("type", transaction.type() == TransactionType.INCOME ? "INCOME" : "EXPENSE");
        params.put("amount", transaction.amount().toPlainString());
        params.put("transactionId", transaction.id().toString());
        String description = transaction.description();
        if (description != null && !description.isBlank()) {
            String clean = description.strip();
            params.put("description", clean.length() > DESCRIPTION_MAX ? clean.substring(0, DESCRIPTION_MAX) : clean);
        }
        return params;
    }
}
