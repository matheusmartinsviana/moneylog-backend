package com.moneylog.core.debt.util;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;

/** Adaptador em memória da porta {@link PersonGateway}, só para teste: guarda a pessoa e a versão, como a persistência faria. */
public class InMemoryPersonGateway implements PersonGateway {

    private final Map<UUID, Person> people = new HashMap<>();
    private final Map<UUID, Long> versions = new HashMap<>();
    private int locks;

    @Override
    public Versioned<Person> insert(Person person) {
        people.put(person.id(), person);
        versions.put(person.id(), 0L);
        return versioned(person);
    }

    @Override
    public Versioned<Person> update(Person person, long expectedVersion) {
        if (!people.containsKey(person.id())) {
            throw new PersonNotFoundException(person.id());
        }
        if (versions.get(person.id()) != expectedVersion) {
            throw new VersionConflictException(expectedVersion, versions.get(person.id()));
        }
        people.put(person.id(), person);
        versions.put(person.id(), expectedVersion + 1);
        return versioned(person);
    }

    @Override
    public void lockForUpdate(UUID userId, UUID id) {
        locks++;
    }

    public int locks() {
        return locks;
    }

    @Override
    public Optional<Versioned<Person>> findById(UUID userId, UUID id) {
        Person person = people.get(id);
        return person == null || !person.userId().equals(userId) ? Optional.empty() : Optional.of(versioned(person));
    }

    @Override
    public Map<UUID, Person> findByIds(UUID userId, Collection<UUID> ids) {
        Map<UUID, Person> found = new HashMap<>();
        ids.forEach(id -> findById(userId, id).ifPresent(v -> found.put(id, v.value())));
        return found;
    }

    @Override
    public Slice<Versioned<Person>> list(UUID userId, Boolean archived, String query, PageQuery page) {
        List<Versioned<Person>> all = new ArrayList<>(people.values().stream().filter(p -> p.userId().equals(userId))
                .filter(p -> archived == null || p.isArchived() == archived)
                .filter(p -> query == null || p.name().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparing((Person p) -> p.name().toLowerCase(Locale.ROOT)).thenComparing(Person::id))
                .map(this::versioned).toList());
        int from = (int) Math.min(page.offset(), all.size());
        int to = (int) Math.min((long) from + page.fetchSize(), all.size());
        return Slice.fromOverfetch(all.subList(from, to), page);
    }

    private Versioned<Person> versioned(Person person) {
        return new Versioned<>(person, versions.get(person.id()), Instant.EPOCH, Instant.EPOCH);
    }
}
