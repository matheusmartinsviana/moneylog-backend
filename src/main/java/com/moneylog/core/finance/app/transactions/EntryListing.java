package com.moneylog.core.finance.app.transactions;

import java.util.List;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.finance.domain.EntryReport;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.transaction.domain.Transaction;

public record EntryListing(Slice<Row> items, Money total, List<CategoryTotal> byCategory, List<DebtRow> debtItems) {

    public EntryListing {
        debtItems = debtItems == null ? List.of() : List.copyOf(debtItems);
    }

    public EntryListing(Slice<Row> items, Money total, List<CategoryTotal> byCategory) {
        this(items, total, byCategory, List.of());
    }

    public record DebtRow(EntryReport.Line line, Category category) {
    }

    public record Row(EntryReport.Line line, Transaction transaction, Category category) {
    }

    public record CategoryTotal(Category category, Money amount) {
    }
}
