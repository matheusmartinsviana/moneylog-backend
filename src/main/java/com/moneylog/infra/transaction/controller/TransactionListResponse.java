package com.moneylog.infra.transaction.controller;

import java.util.List;

import com.moneylog.core.finance.app.transactions.TransactionListing;
import com.moneylog.core.sk.money.Money;

public record TransactionListResponse(List<TransactionResponse> items, boolean hasNext, Totals totals) {

    public record Totals(int count, CashFlow cashFlow) {
    }

    public record CashFlow(Money in, Money out, Money net) {
    }

    public static TransactionListResponse of(TransactionListing listing) {
        return new TransactionListResponse(listing.items().items().stream().map(TransactionResponse::of).toList(),
                listing.items().hasNext(), new Totals(listing.count(),
                new CashFlow(listing.cashFlow().in(), listing.cashFlow().out(), listing.cashFlow().net())));
    }
}
