package com.moneylog.core.debt.util;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.exception.DebtExceptions.DebtNotFoundException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;

/**
 * Adaptador em memória da porta {@link DebtGateway}, só para teste. A dívida guarda as quitações nela mesma (na persistência real
 * elas derivam das movimentações ligadas), então o status e o saldo seguem os do agregado.
 */
public class InMemoryDebtGateway implements DebtGateway {

    private final Map<UUID, Debt> debts = new HashMap<>();
    private final Map<UUID, Long> versions = new HashMap<>();
    private final Map<UUID, LocalDate> settlementDates = new HashMap<>();
    private final List<UUID> locked = new ArrayList<>();

    @Override
    public Versioned<Debt> insert(Debt debt) {
        debts.put(debt.id(), debt);
        versions.put(debt.id(), 0L);
        return versioned(debt);
    }

    @Override
    public Versioned<Debt> update(Debt debt, long expectedVersion) {
        if (!debts.containsKey(debt.id())) {
            throw new DebtNotFoundException(debt.id());
        }
        if (versions.get(debt.id()) != expectedVersion) {
            throw new VersionConflictException(expectedVersion, versions.get(debt.id()));
        }
        debts.put(debt.id(), debt);
        versions.put(debt.id(), expectedVersion + 1);
        return versioned(debt);
    }

    @Override
    public void lockForUpdate(UUID userId, UUID id) {
        locked.add(id);
    }

    public List<UUID> locked() {
        return locked;
    }

    /** Registra a data de uma quitação (a persistência real lê da movimentação). */
    public void settlementDate(UUID transactionId, LocalDate date) {
        settlementDates.put(transactionId, date);
    }

    @Override
    public Optional<Versioned<Debt>> findById(UUID userId, UUID id) {
        Debt debt = debts.get(id);
        return debt == null || !debt.userId().equals(userId) ? Optional.empty() : Optional.of(versioned(debt));
    }

    @Override
    public List<UUID> findIdsByOriginTransaction(UUID userId, UUID transactionId) {
        return findByOriginTransaction(userId, transactionId).stream().map(v -> v.value().id()).toList();
    }

    @Override
    public List<Versioned<Debt>> findByOriginTransaction(UUID userId, UUID transactionId) {
        return debts.values().stream().filter(d -> d.userId().equals(userId) && transactionId.equals(d.originTransactionId()))
                .sorted(Comparator.comparing(Debt::id)).map(this::versioned).toList();
    }

    @Override
    public List<Versioned<Debt>> findByPersons(UUID userId, Collection<UUID> personIds) {
        return debts.values().stream().filter(d -> d.userId().equals(userId) && personIds.contains(d.personId()))
                .sorted(Comparator.comparing(Debt::date).thenComparing(Debt::id).reversed()).map(this::versioned).toList();
    }

    @Override
    public Slice<Versioned<Debt>> list(UUID userId, DebtFilter filter, LocalDate today, PageQuery page) {
        List<Versioned<Debt>> all = debts.values().stream().filter(d -> d.userId().equals(userId))
                .filter(d -> filter.direction() == null || d.direction() == filter.direction())
                .filter(d -> filter.status() == null || d.status() == filter.status())
                .filter(d -> filter.personId() == null || d.personId().equals(filter.personId()))
                .filter(d -> filter.overdue() == null || d.isOverdue(today) == filter.overdue())
                .sorted(Comparator.comparing(Debt::date).thenComparing(Debt::id).reversed()).map(this::versioned).toList();
        int from = (int) Math.min(page.offset(), all.size());
        int to = (int) Math.min((long) from + page.fetchSize(), all.size());
        return Slice.fromOverfetch(all.subList(from, to), page);
    }

    @Override
    public Map<UUID, LocalDate> settlementDates(UUID userId, Collection<UUID> transactionIds) {
        Map<UUID, LocalDate> found = new HashMap<>();
        transactionIds.forEach(id -> {
            if (settlementDates.containsKey(id)) {
                found.put(id, settlementDates.get(id));
            }
        });
        return found;
    }

    private Versioned<Debt> versioned(Debt debt) {
        return new Versioned<>(debt, versions.get(debt.id()), Instant.EPOCH, Instant.EPOCH);
    }
}
