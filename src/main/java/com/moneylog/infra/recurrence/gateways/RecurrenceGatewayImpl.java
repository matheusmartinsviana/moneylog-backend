package com.moneylog.infra.recurrence.gateways;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.persistence.OffsetLimit;
import com.moneylog.infra.recurrence.persistence.RecurrenceEntity;
import com.moneylog.infra.recurrence.persistence.RecurrenceRepositoryJpa;
import com.moneylog.infra.recurrence.persistence.RecurrenceSkipEntity;
import com.moneylog.infra.recurrence.persistence.RecurrenceSkipRepositoryJpa;
import com.moneylog.infra.transaction.persistence.TransactionRepositoryJpa;
import com.moneylog.infra.transaction.persistence.TransactionRepositoryJpa.RealizedRow;

/**
 * {@link RecurrenceGateway} sobre JPA. As ocorrências realizadas <b>não são gravadas aqui</b>: derivam das movimentações ativas com
 * origem em recorrência (S-49), então o estorno da movimentação devolve a ocorrência às pendentes; as puladas ficam em
 * {@code recurrence_skips}.
 */
public class RecurrenceGatewayImpl implements RecurrenceGateway {

    private final RecurrenceRepositoryJpa repository;
    private final RecurrenceSkipRepositoryJpa skips;
    private final TransactionRepositoryJpa transactions;
    private final IdGenerator ids;
    private final TimeProvider time;

    public RecurrenceGatewayImpl(RecurrenceRepositoryJpa repository, RecurrenceSkipRepositoryJpa skips,
                                 TransactionRepositoryJpa transactions, IdGenerator ids, TimeProvider time) {
        this.repository = repository;
        this.skips = skips;
        this.transactions = transactions;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public void lockForUpdate(UUID userId, UUID recurrenceId) {
        repository.lockByIdAndUserId(recurrenceId, userId);
    }

    @Override
    public Optional<RecurringTransaction> findById(UUID userId, UUID recurrenceId) {
        return findVersioned(userId, recurrenceId).map(Versioned::value);
    }

    @Override
    public Optional<Versioned<RecurringTransaction>> findVersioned(UUID userId, UUID recurrenceId) {
        return repository.findByIdAndUserId(recurrenceId, userId).map(entity -> hydrate(userId, List.of(entity)).get(0));
    }

    @Override
    public Versioned<RecurringTransaction> save(RecurringTransaction recurrence) {
        RecurrenceEntity entity = repository.findByIdAndUserId(recurrence.id(), recurrence.userId()).orElse(null);
        java.time.Instant now = time.now();
        if (entity == null) {
            entity = RecurrenceRepositoryMapper.toEntity(recurrence, now);
        } else {
            entity.apply(recurrence.amount().cents(), recurrence.description(), recurrence.categoryId(), recurrence.accountId(),
                    recurrence.cardId(), recurrence.paymentMethod(), recurrence.schedule().endDate(), recurrence.isActive(), now);
        }
        RecurrenceEntity saved = repository.saveAndFlush(entity);
        Set<LocalDate> stored = new HashSet<>();
        skips.findByUserIdAndRecurrenceIdIn(saved.getUserId(), List.of(saved.getId())).forEach(s -> stored.add(s.getOccurrenceDate()));
        List<RecurrenceSkipEntity> toInsert = new ArrayList<>();
        for (LocalDate date : recurrence.skippedDates()) {
            if (!stored.contains(date)) {
                toInsert.add(new RecurrenceSkipEntity(ids.next(), saved.getUserId(), saved.getId(), date, now));
            }
        }
        if (!toInsert.isEmpty()) {
            skips.saveAllAndFlush(toInsert);
        }
        return new Versioned<>(recurrence, saved.getVersion(), saved.getCreatedAt(), saved.getUpdatedAt());
    }

    @Override
    public List<RecurringTransaction> findAll(UUID userId) {
        List<RecurrenceEntity> entities = repository.findByUserId(userId);
        if (entities.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<LocalDate>> skipped = new HashMap<>();
        skips.findByUserId(userId).forEach(s -> skipped.computeIfAbsent(s.getRecurrenceId(), id -> new ArrayList<>()).add(s.getOccurrenceDate()));
        Map<UUID, List<LocalDate>> realized = new HashMap<>();
        transactions.findAllActiveRealized(userId)
                .forEach(r -> realized.computeIfAbsent(r.getRecurrenceId(), id -> new ArrayList<>()).add(r.getDate()));
        return entities.stream().map(e -> RecurrenceRepositoryMapper.toDomain(e, realized.getOrDefault(e.getId(), List.of()),
                skipped.getOrDefault(e.getId(), List.of()))).toList();
    }

    @Override
    public Slice<Versioned<RecurringTransaction>> list(UUID userId, Boolean active, PageQuery page) {
        if (page.isBeyondAnyData()) {
            return new Slice<>(List.of(), false);
        }
        List<RecurrenceEntity> fetched = repository.findPage(userId, active, OffsetLimit.of(page));
        List<RecurrenceEntity> pageEntities = fetched.size() > page.pageSize() ? fetched.subList(0, page.pageSize()) : fetched;
        return new Slice<>(hydrate(userId, pageEntities), fetched.size() > page.pageSize());
    }

    @Override
    public Map<UUID, Map<LocalDate, RealizedOccurrence>> realizedOccurrences(UUID userId, Collection<UUID> recurrenceIds) {
        Map<UUID, Map<LocalDate, RealizedOccurrence>> result = new HashMap<>();
        if (recurrenceIds.isEmpty()) {
            return result;
        }
        for (RealizedRow row : transactions.findActiveRealized(userId, recurrenceIds)) {
            result.computeIfAbsent(row.getRecurrenceId(), id -> new HashMap<>()).put(row.getDate(),
                    new RealizedOccurrence(row.getId(), Money.ofCents(row.getAmountCents(), CurrencyCode.BRL)));
        }
        return result;
    }

    private List<Versioned<RecurringTransaction>> hydrate(UUID userId, List<RecurrenceEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = entities.stream().map(RecurrenceEntity::getId).toList();
        Map<UUID, List<LocalDate>> skipped = new HashMap<>();
        skips.findByUserIdAndRecurrenceIdIn(userId, ids)
                .forEach(s -> skipped.computeIfAbsent(s.getRecurrenceId(), id -> new ArrayList<>()).add(s.getOccurrenceDate()));
        Map<UUID, List<LocalDate>> realized = new HashMap<>();
        transactions.findActiveRealized(userId, ids)
                .forEach(r -> realized.computeIfAbsent(r.getRecurrenceId(), id -> new ArrayList<>()).add(r.getDate()));
        return entities.stream().map(e -> RecurrenceRepositoryMapper.toVersioned(e, realized.getOrDefault(e.getId(), List.of()),
                skipped.getOrDefault(e.getId(), List.of()))).toList();
    }
}
