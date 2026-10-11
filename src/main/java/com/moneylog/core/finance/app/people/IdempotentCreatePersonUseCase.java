package com.moneylog.core.finance.app.people;

import java.util.UUID;

import com.moneylog.core.debt.app.PersonView;
import com.moneylog.core.debt.app.PersonViews;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.app.usecase.CreatePersonUseCase;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.IdempotentClaim;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;

public class IdempotentCreatePersonUseCase implements CreatePersonUseCase {

    private final CreatePersonUseCase delegate;
    private final PersonGateway people;
    private final DebtGateway debts;
    private final IdempotencyGateway idempotency;
    private final IdGenerator ids;
    private final TimeProvider time;

    @SuppressWarnings("java:S107")
    public IdempotentCreatePersonUseCase(CreatePersonUseCase delegate, PersonGateway people, DebtGateway debts, IdempotencyGateway idempotency,
                                         IdGenerator ids, TimeProvider time) {
        this.delegate = delegate;
        this.people = people;
        this.debts = debts;
        this.idempotency = idempotency;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public PersonView handle(CreatePersonCommand command) {
        if (command.idempotencyKey() == null) {
            return delegate.handle(command);
        }
        String hash = IdempotentClaim.hash("name=" + (command.name() == null ? "-" : command.name().strip()) + ";notes="
                + (command.notes() == null ? "-" : command.notes().strip()) + ";");
        UUID reserved = ids.next();
        Claim existing = IdempotentClaim.claimOrFind(idempotency, command.userId(), IDEMPOTENCY_SCOPE, command.idempotencyKey(), hash, reserved,
                time.now());
        if (existing != null) {
            IdempotentClaim.requireSameCommand(existing, hash);
            Versioned<Person> person = people.findById(command.userId(), existing.resourceId())
                    .orElseThrow(() -> new PersonNotFoundException(existing.resourceId()));
            return PersonViews.one(debts, command.userId(), person, time.today(), false);
        }
        Versioned<Person> saved = people.insert(Person.create(reserved, command.userId(), command.name(), command.notes()));
        return PersonViews.one(debts, command.userId(), saved, time.today(), false);
    }
}
