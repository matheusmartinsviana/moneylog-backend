package com.moneylog.core.debt.app.usecase;

import com.moneylog.core.debt.app.PersonView;
import com.moneylog.core.debt.app.PersonViews;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class ListPeopleUseCaseImpl implements ListPeopleUseCase {

    private final PersonGateway people;
    private final DebtGateway debts;
    private final TimeProvider time;

    public ListPeopleUseCaseImpl(PersonGateway people, DebtGateway debts, TimeProvider time) {
        this.people = people;
        this.debts = debts;
        this.time = time;
    }

    @Override
    public Slice<PersonView> handle(ListPeopleQuery query) {
        Slice<Versioned<Person>> page = people.list(query.userId(), query.archived(), query.query(), query.page());
        return new Slice<>(PersonViews.of(debts, query.userId(), page.items(), time.today(), false), page.hasNext());
    }
}
