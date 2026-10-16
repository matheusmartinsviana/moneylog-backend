package com.moneylog.core.income.app.usecase;

import java.util.List;

import com.moneylog.core.income.domain.TaxTable;

public interface ListTaxTablesUseCase {

    List<TaxTable> handle(ListTaxTablesQuery query);

    record ListTaxTablesQuery(int year) {
    }
}
