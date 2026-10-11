package com.moneylog.infra.person.gateways;

import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.infra.person.persistence.PersonEntity;

public final class PersonRepositoryMapper {

    private PersonRepositoryMapper() {
    }

    public static Person toDomain(PersonEntity e) {
        return Person.restore(e.getId(), e.getUserId(), e.getName(), e.getNotes(), e.isArchived());
    }

    public static Versioned<Person> toVersioned(PersonEntity e) {
        return new Versioned<>(toDomain(e), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
