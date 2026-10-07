package com.moneylog.core.debt.app.usecase;

import com.moneylog.core.debt.app.PersonView;
import com.moneylog.core.debt.app.PersonViews;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.sk.time.TimeProvider;

public class GetPersonUseCaseImpl implements GetPersonUseCase {

    private final PersonGateway people;
    private final DebtGateway debts;
    private final TimeProvider time;

    public GetPersonUseCaseImpl(PersonGateway people, DebtGateway debts, TimeProvider time) {
        this.people = people;
        this.debts = debts;
        this.time = time;
    }

    @Override
    public PersonView handle(GetPersonQuery query) {
        return PersonViews.one(debts, query.userId(), people.findById(query.userId(), query.personId())
                .orElseThrow(() -> new PersonNotFoundException(query.personId())), time.today(), true);
    }
}
