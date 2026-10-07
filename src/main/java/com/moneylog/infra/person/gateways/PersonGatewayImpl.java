package com.moneylog.infra.person.gateways;

import java.text.Normalizer;
import java.util.Collection;
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
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.person.persistence.PersonEntity;
import com.moneylog.infra.person.persistence.PersonRepositoryJpa;
import com.moneylog.infra.persistence.OffsetLimit;

public class PersonGatewayImpl implements PersonGateway {

    private final PersonRepositoryJpa repository;
    private final TimeProvider time;

    public PersonGatewayImpl(PersonRepositoryJpa repository, TimeProvider time) {
        this.repository = repository;
        this.time = time;
    }

    @Override
    public Versioned<Person> insert(Person person) {
        PersonEntity entity = new PersonEntity(person.id(), person.userId(), person.name(), person.notes(), person.isArchived(),
                time.now());
        return PersonRepositoryMapper.toVersioned(repository.saveAndFlush(entity));
    }

    @Override
    public Versioned<Person> update(Person person, long expectedVersion) {
        PersonEntity entity = repository.findByIdAndUserId(person.id(), person.userId())
                .orElseThrow(() -> new PersonNotFoundException(person.id()));
        if (entity.getVersion() != expectedVersion) {
            throw new VersionConflictException(expectedVersion, entity.getVersion());
        }
        entity.apply(person.name(), person.notes(), person.isArchived(), time.now());
        return PersonRepositoryMapper.toVersioned(repository.saveAndFlush(entity));
    }

    @Override
    public void lockForUpdate(UUID userId, UUID id) {
        repository.lockByIdAndUserId(id, userId);
    }

    @Override
    public Optional<Versioned<Person>> findById(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId).map(PersonRepositoryMapper::toVersioned);
    }

    @Override
    public Map<UUID, Person> findByIds(UUID userId, Collection<UUID> ids) {
        Map<UUID, Person> byId = new HashMap<>();
        if (ids.isEmpty()) {
            return byId;
        }
        repository.findByUserIdAndIdIn(userId, ids).forEach(e -> byId.put(e.getId(), PersonRepositoryMapper.toDomain(e)));
        return byId;
    }

    @Override
    public Slice<Versioned<Person>> list(UUID userId, Boolean archived, String query, PageQuery page) {
        if (page.isBeyondAnyData()) {
            return new Slice<>(List.of(), false);
        }
        String needle = query == null || query.isBlank() ? null : fold(query);
        if (needle == null) {
            return Slice.fromOverfetch(repository.findPage(userId, archived, OffsetLimit.of(page)).stream()
                    .map(PersonRepositoryMapper::toVersioned).toList(), page);
        }
        // a busca ignora caixa e acentos: a lista de pessoas é curta, então o filtro é feito aqui (sem extensão do banco)
        List<Versioned<Person>> matches = repository.findPage(userId, archived, org.springframework.data.domain.Pageable.unpaged())
                .stream().filter(e -> fold(e.getName()).contains(needle)).map(PersonRepositoryMapper::toVersioned).toList();
        int from = (int) Math.min(page.offset(), matches.size());
        int to = (int) Math.min((long) from + page.fetchSize(), matches.size());
        return Slice.fromOverfetch(matches.subList(from, to), page);
    }

    /** Minúsculas e sem acentos. */
    static String fold(String text) {
        return Normalizer.normalize(text.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }
}
