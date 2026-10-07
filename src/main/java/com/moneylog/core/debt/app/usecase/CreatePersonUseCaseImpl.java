package com.moneylog.core.debt.app.usecase;

import com.moneylog.core.debt.app.PersonView;
import com.moneylog.core.debt.app.PersonViews;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class CreatePersonUseCaseImpl implements CreatePersonUseCase {

    private final PersonGateway people;
    private final DebtGateway debts;
    private final IdGenerator ids;
    private final TimeProvider time;

    public CreatePersonUseCaseImpl(PersonGateway people, DebtGateway debts, IdGenerator ids, TimeProvider time) {
        this.people = people;
        this.debts = debts;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public PersonView handle(CreatePersonCommand command) {
        Person person = Person.create(ids.next(), command.userId(), command.name(), command.notes());
        Versioned<Person> saved = people.insert(person);
        return PersonViews.one(debts, command.userId(), saved, time.today(), false);
    }
}
