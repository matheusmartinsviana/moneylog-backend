package com.moneylog.core.finance.app.transactions.usecase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.transactions.EntryListing;
import com.moneylog.core.finance.app.transactions.EntryListing.CategoryTotal;
import com.moneylog.core.finance.app.transactions.EntryListing.Row;
import com.moneylog.core.finance.domain.EntryReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.transaction.domain.Transaction;

public class ListEntriesUseCaseImpl implements ListEntriesUseCase {

    private final FinancialRecordsGateway records;
    private final CategoryGateway categories;

    public ListEntriesUseCaseImpl(FinancialRecordsGateway records, CategoryGateway categories) {
        this.records = records;
        this.categories = categories;
    }

    @Override
    public EntryListing handle(ListEntriesQuery query) {
        FinancialRecords loaded = records.load(query.userId());
        EntryReport report = EntryReport.of(loaded, loaded.ledger(), query.lens(), query.competence(), query.filter().predicate(),
                query.filter().debtCategoryPredicate());
        Map<UUID, Category> categoriesById = categories.list(query.userId(), null, true).stream()
                .collect(Collectors.toMap(Category::id, Function.identity()));
        Map<UUID, Transaction> transactionsById = loaded.transactions().stream()
                .collect(Collectors.toMap(Transaction::id, Function.identity()));

        List<EntryReport.Line> sorted = report.lines().stream().filter(line -> line.debtId() == null)
                .sorted(Comparator.comparing(EntryReport.Line::date).reversed()
                        .thenComparing(EntryReport.Line::transactionId)
                        .thenComparing(line -> line.installmentNumber() == null ? 0 : line.installmentNumber()))
                .toList();
        PageQuery page = query.page();
        int from = (int) Math.min(page.offset(), sorted.size());
        List<Row> rows = new ArrayList<>();
        for (EntryReport.Line line : sorted.subList(from, Math.min(sorted.size(), from + page.fetchSize()))) {
            rows.add(new Row(line, transactionsById.get(line.transactionId()), category(categoriesById, line.categoryId())));
        }
        List<CategoryTotal> byCategory = report.byCategory().entrySet().stream()
                .map(entry -> new CategoryTotal(category(categoriesById, entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparing((CategoryTotal t) -> t.amount()).reversed().thenComparing(t -> t.category().name()))
                .toList();
        List<EntryListing.DebtRow> debtRows = page.offset() > 0 ? List.of() : report.lines().stream().filter(line -> line.debtId() != null)
                .sorted(Comparator.comparing(EntryReport.Line::date).reversed().thenComparing(EntryReport.Line::debtId))
                .map(line -> new EntryListing.DebtRow(line, category(categoriesById, line.categoryId()))).toList();
        return new EntryListing(Slice.fromOverfetch(rows, page), report.total(), byCategory, debtRows);
    }

    private static Category category(Map<UUID, Category> categoriesById, UUID id) {
        Category category = categoriesById.get(id);
        if (category == null) {
            throw new CategoryNotFoundException(id);
        }
        return category;
    }
}
