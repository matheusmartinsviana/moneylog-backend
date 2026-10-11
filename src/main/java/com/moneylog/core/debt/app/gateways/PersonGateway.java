package com.moneylog.core.debt.app.gateways;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;

public interface PersonGateway {

    Versioned<Person> insert(Person person);

    Versioned<Person> update(Person person, long expectedVersion);

    void lockForUpdate(UUID userId, UUID id);

    Optional<Versioned<Person>> findById(UUID userId, UUID id);

    Map<UUID, Person> findByIds(UUID userId, Collection<UUID> ids);

    Slice<Versioned<Person>> list(UUID userId, Boolean archived, String query, PageQuery page);
}
