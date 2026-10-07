package com.moneylog.core.finance.app.reports.usecase;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.EntryLens;
import com.moneylog.core.finance.domain.EntryReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.Ledger;
import com.moneylog.core.finance.domain.SpendingByCategory;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.TimeProvider;

/** Sem classificação própria (S-79): {@link EntryReport} da lente de consumo e só {@link SpendingByCategory#of}. */
public class GetSpendingByCategoryUseCaseImpl implements GetSpendingByCategoryUseCase {

    private final FinancialRecordsGateway records;
    private final CategoryGateway categories;
    private final TimeProvider time;

    public GetSpendingByCategoryUseCaseImpl(FinancialRecordsGateway records, CategoryGateway categories, TimeProvider time) {
        this.records = records;
        this.categories = categories;
        this.time = time;
    }

    @Override
    public SpendingByCategoryView handle(GetSpendingByCategoryQuery query) {
        FinancialRecords loaded = records.load(query.userId());
        Ledger ledger = loaded.ledger();
        Competence month = query.month() == null ? Competence.from(time.today()) : query.month();
        Map<UUID, Category> byId = categories.list(query.userId(), null, true).stream()
                .collect(Collectors.toMap(Category::id, Function.identity()));
        EntryReport consumption = EntryReport.of(loaded, ledger, EntryLens.CONSUMPTION, month);
        SpendingByCategory spending = SpendingByCategory.of(consumption, query.limit(), id -> byId.containsKey(id) ? byId.get(id).name() : "");
        return new SpendingByCategoryView(spending, byId);
    }
}
