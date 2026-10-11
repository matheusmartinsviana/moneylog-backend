package com.moneylog.core.finance.app.summary;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.MonthlySummary;
import com.moneylog.core.sk.time.TimeProvider;

public class GetMonthlySummaryUseCaseImpl implements GetMonthlySummaryUseCase {

    private final FinancialRecordsGateway records;
    private final CategoryGateway categories;
    private final TimeProvider time;

    public GetMonthlySummaryUseCaseImpl(FinancialRecordsGateway records, CategoryGateway categories, TimeProvider time) {
        this.records = records;
        this.categories = categories;
        this.time = time;
    }

    @Override
    public MonthlySummaryView handle(GetMonthlySummaryQuery query) {
        FinancialRecords loaded = records.load(query.userId());
        MonthlySummary summary = MonthlySummary.of(loaded, loaded.ledger(), query.month(), time.today());
        Map<UUID, Category> byId = categories.list(query.userId(), null, true).stream()
                .collect(Collectors.toMap(Category::id, Function.identity()));
        Category largest = summary.largestCategory() == null ? null : byId.get(summary.largestCategory().categoryId());
        Category ofExpense = summary.largestExpense() == null ? null : byId.get(summary.largestExpense().categoryId());
        return new MonthlySummaryView(summary, largest, ofExpense);
    }
}
