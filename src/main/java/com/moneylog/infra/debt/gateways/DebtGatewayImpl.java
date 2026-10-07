package com.moneylog.infra.debt.gateways;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.exception.DebtExceptions.DebtNotFoundException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.debt.persistence.DebtEntity;
import com.moneylog.infra.debt.persistence.DebtRepositoryJpa;
import com.moneylog.infra.transaction.persistence.TransactionRepositoryJpa;

public class DebtGatewayImpl implements DebtGateway {

    private final DebtRepositoryJpa repository;
    private final TransactionRepositoryJpa transactions;
    private final DebtHydrator hydrator;
    private final JdbcTemplate jdbc;
    private final TimeProvider time;

    public DebtGatewayImpl(DebtRepositoryJpa repository, TransactionRepositoryJpa transactions, DebtHydrator hydrator,
                           JdbcTemplate jdbc, TimeProvider time) {
        this.repository = repository;
        this.transactions = transactions;
        this.hydrator = hydrator;
        this.jdbc = jdbc;
        this.time = time;
    }

    @Override
    public Versioned<Debt> insert(Debt debt) {
        DebtEntity saved = repository.saveAndFlush(DebtRepositoryMapper.toEntity(debt, time.now()));
        return new Versioned<>(debt, saved.getVersion(), saved.getCreatedAt(), saved.getUpdatedAt());
    }

    @Override
    public Versioned<Debt> update(Debt debt, long expectedVersion) {
        DebtEntity entity = repository.findByIdAndUserId(debt.id(), debt.userId())
                .orElseThrow(() -> new DebtNotFoundException(debt.id()));
        if (entity.getVersion() != expectedVersion) {
            throw new VersionConflictException(expectedVersion, entity.getVersion());
        }
        entity.apply(debt.description(), debt.expectedDate(), debt.closure(), debt.closureDate(), debt.closureReason(), time.now());
        DebtEntity saved = repository.saveAndFlush(entity);
        return new Versioned<>(debt, saved.getVersion(), saved.getCreatedAt(), saved.getUpdatedAt());
    }

    @Override
    public void lockForUpdate(UUID userId, UUID id) {
        repository.lockByIdAndUserId(id, userId);
    }

    @Override
    public Optional<Versioned<Debt>> findById(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId).map(entity -> versioned(userId, List.of(entity)).get(0));
    }

    @Override
    public List<UUID> findIdsByOriginTransaction(UUID userId, UUID transactionId) {
        return repository.findIdsByOrigin(userId, transactionId);
    }

    @Override
    public List<Versioned<Debt>> findByOriginTransaction(UUID userId, UUID transactionId) {
        return versioned(userId, repository.findByUserIdAndOriginTransactionId(userId, transactionId));
    }

    @Override
    public List<Versioned<Debt>> findByPersons(UUID userId, Collection<UUID> personIds) {
        if (personIds.isEmpty()) {
            return List.of();
        }
        return versioned(userId, repository.findByUserIdAndPersonIdIn(userId, personIds));
    }

    @Override
    public Slice<Versioned<Debt>> list(UUID userId, DebtFilter filter, LocalDate today, PageQuery page) {
        if (page.isBeyondAnyData()) {
            return new Slice<>(List.of(), false);
        }
        StringBuilder sql = new StringBuilder("""
                SELECT d.id FROM debts d
                LEFT JOIN LATERAL (
                    SELECT COALESCE(SUM(t.amount_cents), 0) AS settled FROM transactions t
                    WHERE t.debt_id = d.id AND t.status = 'ACTIVE' AND t.id IS DISTINCT FROM d.origin_transaction_id) s ON TRUE
                WHERE d.user_id = ?""");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (filter.direction() != null) {
            sql.append(" AND d.direction = ?");
            args.add(filter.direction().name());
        }
        if (filter.personId() != null) {
            sql.append(" AND d.person_id = ?");
            args.add(filter.personId());
        }
        if (filter.status() != null) {
            sql.append(switch (filter.status()) {
                case OPEN -> " AND d.closure IS NULL AND s.settled = 0";
                case PARTIALLY_SETTLED -> " AND d.closure IS NULL AND s.settled > 0 AND s.settled < d.principal_cents";
                case SETTLED -> " AND d.closure IS NULL AND s.settled >= d.principal_cents";
                case CANCELED -> " AND d.closure = 'CANCELED'";
                case VOIDED -> " AND d.closure = 'VOIDED'";
            });
        }
        if (Boolean.TRUE.equals(filter.overdue())) {
            sql.append(" AND d.closure IS NULL AND d.expected_date < ? AND s.settled < d.principal_cents");
            args.add(Date.valueOf(today));
        } else if (Boolean.FALSE.equals(filter.overdue())) {
            sql.append(" AND NOT (d.closure IS NULL AND d.expected_date < ? AND s.settled < d.principal_cents)");
            args.add(Date.valueOf(today));
        }
        sql.append(" ORDER BY d.debt_date DESC, d.id DESC LIMIT ? OFFSET ?");
        args.add(page.fetchSize());
        args.add(page.offset());
        List<UUID> ids = jdbc.query(sql.toString(), (rs, i) -> rs.getObject(1, UUID.class), args.toArray());
        List<Versioned<Debt>> ordered = ordered(userId, ids);
        return Slice.fromOverfetch(ordered, page);
    }

    @Override
    public Map<UUID, LocalDate> settlementDates(UUID userId, Collection<UUID> transactionIds) {
        Map<UUID, LocalDate> dates = new HashMap<>();
        if (transactionIds.isEmpty()) {
            return dates;
        }
        transactions.findAllById(transactionIds).stream().filter(t -> t.getUserId().equals(userId))
                .forEach(t -> dates.put(t.getId(), t.getTransactionDate()));
        return dates;
    }

    private List<Versioned<Debt>> ordered(UUID userId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, Versioned<Debt>> byId = new HashMap<>();
        versioned(userId, repository.findByUserIdAndIdIn(userId, ids)).forEach(v -> byId.put(v.value().id(), v));
        return ids.stream().map(byId::get).toList();
    }

    private List<Versioned<Debt>> versioned(UUID userId, List<DebtEntity> entities) {
        List<Debt> debts = hydrator.toDomain(userId, entities);
        List<Versioned<Debt>> result = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            DebtEntity e = entities.get(i);
            result.add(new Versioned<>(debts.get(i), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt()));
        }
        result.sort(java.util.Comparator.comparing((Versioned<Debt> v) -> v.value().date())
                .thenComparing(v -> v.value().id()).reversed());
        return result;
    }
}
