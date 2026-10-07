package com.moneylog.core.debt.app.usecase;

import com.moneylog.core.debt.app.PersonView;
import com.moneylog.core.debt.app.PersonViews;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class UpdatePersonUseCaseImpl implements UpdatePersonUseCase {

    private final PersonGateway people;
    private final DebtGateway debts;
    private final TimeProvider time;

    public UpdatePersonUseCaseImpl(PersonGateway people, DebtGateway debts, TimeProvider time) {
        this.people = people;
        this.debts = debts;
        this.time = time;
    }

    @Override
    public PersonView handle(UpdatePersonCommand command) {
        people.lockForUpdate(command.userId(), command.personId());
        Versioned<Person> current = people.findById(command.userId(), command.personId())
                .orElseThrow(() -> new PersonNotFoundException(command.personId()));
        if (current.version() != command.version()) {
            throw new VersionConflictException(command.version(), current.version());
        }
        Person person = current.value();
        person.rename(command.name());
        person.changeNotes(command.notes());
        Versioned<Person> saved = people.update(person, command.version());
        return PersonViews.one(debts, command.userId(), saved, time.today(), false);
    }
}
