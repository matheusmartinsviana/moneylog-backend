package com.moneylog.core.debt.app.gateways;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;

public interface DebtGateway {

    Versioned<Debt> insert(Debt debt);

    Versioned<Debt> update(Debt debt, long expectedVersion);

    void lockForUpdate(UUID userId, UUID id);

    Optional<Versioned<Debt>> findById(UUID userId, UUID id);

    List<UUID> findIdsByOriginTransaction(UUID userId, UUID transactionId);

    List<Versioned<Debt>> findByOriginTransaction(UUID userId, UUID transactionId);

    List<Versioned<Debt>> findByPersons(UUID userId, Collection<UUID> personIds);

    Slice<Versioned<Debt>> list(UUID userId, DebtFilter filter, LocalDate today, PageQuery page);

    Map<UUID, LocalDate> settlementDates(UUID userId, Collection<UUID> transactionIds);

    Map<UUID, List<Money>> installmentSchedules(UUID userId, Collection<UUID> transactionIds);

    record DebtFilter(DebtDirection direction, DebtStatus status, UUID personId, Boolean overdue) {
        public static DebtFilter all() {
            return new DebtFilter(null, null, null, null);
        }
    }
}
