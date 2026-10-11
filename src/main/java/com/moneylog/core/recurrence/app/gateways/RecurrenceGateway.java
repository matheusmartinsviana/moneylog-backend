package com.moneylog.core.recurrence.app.gateways;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;

public interface RecurrenceGateway {

    record RealizedOccurrence(UUID transactionId, Money amount) {
    }

    void lockForUpdate(UUID userId, UUID recurrenceId);

    Optional<RecurringTransaction> findById(UUID userId, UUID recurrenceId);

    Optional<Versioned<RecurringTransaction>> findVersioned(UUID userId, UUID recurrenceId);

    Versioned<RecurringTransaction> save(RecurringTransaction recurrence);

    List<RecurringTransaction> findAll(UUID userId);

    Slice<Versioned<RecurringTransaction>> list(UUID userId, Boolean active, PageQuery page);

    Map<UUID, Map<LocalDate, RealizedOccurrence>> realizedOccurrences(UUID userId, Collection<UUID> recurrenceIds);
}
