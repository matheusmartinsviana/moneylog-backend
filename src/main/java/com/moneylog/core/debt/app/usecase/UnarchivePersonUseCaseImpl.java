package com.moneylog.core.debt.app.usecase;

import com.moneylog.core.debt.app.PersonView;
import com.moneylog.core.debt.app.PersonViews;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

/** Unarchive: a pessoa já arquivada/ativa responde {@code PERSON_ALREADY_ARCHIVED}/{@code PERSON_NOT_ARCHIVED} (a regra é do domínio). */
public class UnarchivePersonUseCaseImpl implements UnarchivePersonUseCase {

    private final PersonGateway people;
    private final DebtGateway debts;
    private final TimeProvider time;

    public UnarchivePersonUseCaseImpl(PersonGateway people, DebtGateway debts, TimeProvider time) {
        this.people = people;
        this.debts = debts;
        this.time = time;
    }

    @Override
    public PersonView handle(UnarchivePersonCommand command) {
        people.lockForUpdate(command.userId(), command.personId());
        Versioned<Person> current = people.findById(command.userId(), command.personId())
                .orElseThrow(() -> new PersonNotFoundException(command.personId()));
        Person person = current.value();
        person.unarchive();
        Versioned<Person> saved = people.update(person, current.version());
        return PersonViews.one(debts, command.userId(), saved, time.today(), false);
    }
}
