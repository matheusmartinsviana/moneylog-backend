package com.moneylog.core.finance.app.transactions.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionListing;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.transaction.app.query.TransactionFilter;

public interface ListTransactionsUseCase {

    TransactionListing handle(ListTransactionsQuery query);

    record ListTransactionsQuery(UUID userId, TransactionFilter filter, PageQuery page) {
    }
}
