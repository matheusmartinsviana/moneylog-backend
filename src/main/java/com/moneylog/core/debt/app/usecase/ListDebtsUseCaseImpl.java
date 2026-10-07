package com.moneylog.core.debt.app.usecase;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.debt.app.DebtView;
import com.moneylog.core.debt.app.DebtViews;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class ListDebtsUseCaseImpl implements ListDebtsUseCase {

    private final DebtGateway debts;
    private final PersonGateway people;
    private final CategoryGateway categories;
    private final TimeProvider time;

    public ListDebtsUseCaseImpl(DebtGateway debts, PersonGateway people, CategoryGateway categories, TimeProvider time) {
        this.debts = debts;
        this.people = people;
        this.categories = categories;
        this.time = time;
    }

    @Override
    public Slice<DebtView> handle(ListDebtsQuery query) {
        Slice<Versioned<Debt>> page = debts.list(query.userId(), query.filter(), time.today(), query.page());
        return new Slice<>(DebtViews.of(people, debts, categories, query.userId(), page.items(), time.today()), page.hasNext());
    }
}
