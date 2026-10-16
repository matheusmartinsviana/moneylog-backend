package com.moneylog.core.income.app.usecase;

import java.util.List;

import com.moneylog.core.income.app.gateways.TaxTableGateway;
import com.moneylog.core.income.domain.TaxTable;

public class ListTaxTablesUseCaseImpl implements ListTaxTablesUseCase {

    private final TaxTableGateway tables;

    public ListTaxTablesUseCaseImpl(TaxTableGateway tables) {
        this.tables = tables;
    }

    @Override
    public List<TaxTable> handle(ListTaxTablesQuery query) {
        return tables.listForYear(query.year());
    }
}
