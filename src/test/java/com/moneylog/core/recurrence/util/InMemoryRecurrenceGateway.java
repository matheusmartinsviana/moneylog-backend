package com.moneylog.core.recurrence.util;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionSource;

public class InMemoryRecurrenceGateway implements RecurrenceGateway {

    private final Map<UUID, RecurringTransaction> models = new HashMap<>();
    private final Map<UUID, Long> versions = new HashMap<>();
    private final Supplier<Collection<Transaction>> transactions;
    private int locks;

    public InMemoryRecurrenceGateway(Supplier<Collection<Transaction>> transactions) {
        this.transactions = transactions;
    }

    @Override
    public void lockForUpdate(UUID userId, UUID recurrenceId) {
        locks++;
    }

    public int locks() {
        return locks;
    }

    @Override
    public Optional<RecurringTransaction> findById(UUID userId, UUID recurrenceId) {
        RecurringTransaction model = models.get(recurrenceId);
        if (model == null || !model.userId().equals(userId)) {
            return Optional.empty();
        }
        List<LocalDate> realized = transactions.get().stream()
                .filter(t -> t.isActive() && t.origin().source() == TransactionSource.RECURRENCE
                        && recurrenceId.equals(t.origin().recurrenceId()))
                .map(t -> t.origin().occurrenceDate()).toList();
        RecurringTransaction restored = RecurringTransaction.restore(model.id(), model.userId(), model.type(), model.amount(),
                model.description(), model.categoryId(), model.accountId(), model.cardId(), model.paymentMethod(), model.schedule(),
                realized, model.skippedDates(), model.isActive());
        restored.defineForeignBasis(model.foreignBasis());
        restored.defineSubscriptionTraits(model.traits());
        return Optional.of(restored);
    }

    @Override
    public Optional<Versioned<RecurringTransaction>> findVersioned(UUID userId, UUID recurrenceId) {
        return findById(userId, recurrenceId).map(r -> new Versioned<>(r, versions.getOrDefault(recurrenceId, 0L),
                java.time.Instant.EPOCH, java.time.Instant.EPOCH));
    }

    @Override
    public Versioned<RecurringTransaction> save(RecurringTransaction recurrence) {
        models.put(recurrence.id(), recurrence);
        long version = versions.containsKey(recurrence.id()) ? versions.get(recurrence.id()) + 1 : 0L;
        versions.put(recurrence.id(), version);
        return new Versioned<>(recurrence, version, java.time.Instant.EPOCH, java.time.Instant.EPOCH);
    }

    @Override
    public List<Versioned<RecurringTransaction>> findAllVersioned(UUID userId) {
        return findAll(userId).stream().map(r -> findVersioned(userId, r.id()).orElseThrow()).toList();
    }

    @Override
    public List<RecurringTransaction> findAll(UUID userId) {
        return models.keySet().stream().map(id -> findById(userId, id)).flatMap(Optional::stream).toList();
    }

    @Override
    public Slice<Versioned<RecurringTransaction>> list(UUID userId, Boolean active, PageQuery page) {
        List<Versioned<RecurringTransaction>> all = findAll(userId).stream().filter(r -> active == null || r.isActive() == active)
                .map(r -> findVersioned(userId, r.id()).orElseThrow()).toList();
        int from = (int) Math.min(page.offset(), all.size());
        int to = (int) Math.min((long) from + page.fetchSize(), all.size());
        return Slice.fromOverfetch(all.subList(from, to), page);
    }

    @Override
    public Map<UUID, Map<LocalDate, RealizedOccurrence>> realizedOccurrences(UUID userId, Collection<UUID> recurrenceIds) {
        Map<UUID, Map<LocalDate, RealizedOccurrence>> result = new HashMap<>();
        for (Transaction t : transactions.get()) {
            if (t.isActive() && t.origin().source() == TransactionSource.RECURRENCE && recurrenceIds.contains(t.origin().recurrenceId())) {
                result.computeIfAbsent(t.origin().recurrenceId(), id -> new HashMap<>())
                        .put(t.origin().occurrenceDate(), new RealizedOccurrence(t.id(), t.amount()));
            }
        }
        return result;
    }
}
