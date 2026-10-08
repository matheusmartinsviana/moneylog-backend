package com.moneylog.infra.goal.gateways;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.moneylog.core.goal.app.gateways.GoalSnapshotGateway;
import com.moneylog.core.goal.domain.GoalSnapshot;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

/** {@link GoalSnapshotGateway} sobre JDBC: um registro por meta e mês ({@code UNIQUE (goal_id, reference_month)}), então repetir é idempotente. */
public class GoalSnapshotGatewayImpl implements GoalSnapshotGateway {

    private static final Logger log = LoggerFactory.getLogger(GoalSnapshotGatewayImpl.class);

    private static final String UPSERT = "INSERT INTO goal_metric_snapshots (id, user_id, goal_id, reference_month, value_cents, currency,"
            + " captured_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'BRL', ?, ?, ?)"
            + " ON CONFLICT (goal_id, reference_month) DO UPDATE SET value_cents = EXCLUDED.value_cents,"
            + " captured_at = EXCLUDED.captured_at, updated_at = EXCLUDED.updated_at";
    private static final String INSERT_IF_ABSENT = "INSERT INTO goal_metric_snapshots (id, user_id, goal_id, reference_month, value_cents,"
            + " currency, captured_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'BRL', ?, ?, ?)"
            + " ON CONFLICT (goal_id, reference_month) DO NOTHING";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate ownTransaction;

    public GoalSnapshotGatewayImpl(JdbcTemplate jdbc, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(tm);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Map<UUID, List<GoalSnapshot>> findByGoals(UUID userId, Collection<UUID> goalIds) {
        Map<UUID, List<GoalSnapshot>> byGoal = new HashMap<>();
        if (goalIds.isEmpty()) {
            return byGoal;
        }
        Collection<UUID> wanted = new java.util.HashSet<>(goalIds);
        jdbc.query("SELECT goal_id, reference_month, value_cents, captured_at FROM goal_metric_snapshots WHERE user_id = ?", rs -> {
            UUID goalId = rs.getObject(1, UUID.class);
            if (wanted.contains(goalId)) {
                byGoal.computeIfAbsent(goalId, k -> new ArrayList<>()).add(new GoalSnapshot(goalId,
                        Competence.from(rs.getObject(2, LocalDate.class)), Money.ofCents(rs.getLong(3), CurrencyCode.BRL),
                        rs.getObject(4, OffsetDateTime.class).toInstant()));
            }
        }, userId);
        return byGoal;
    }

    @Override
    public void upsert(UUID userId, UUID goalId, Competence month, Money value, Instant capturedAt) {
        write(UPSERT, userId, goalId, month, value, capturedAt);
    }

    @Override
    public void touchIfAbsent(UUID userId, UUID goalId, Competence month, Money value, Instant capturedAt) {
        try {
            ownTransaction.executeWithoutResult(status -> write(INSERT_IF_ABSENT, userId, goalId, month, value, capturedAt));
        } catch (RuntimeException e) {
            // ler a meta nunca falha por causa do instantâneo (o job diário cobre); sem dados no log
            log.warn("Instantâneo da meta não gravado na leitura (erro={})", e.getClass().getSimpleName());
        }
    }

    @Override
    public List<UUID> userIdsWithTrackedGoals() {
        return jdbc.queryForList("SELECT DISTINCT user_id FROM goals WHERE archived = FALSE AND kind IN ('METRIC', 'DEBT_PAYOFF')"
                + " AND (kind = 'METRIC' OR baseline_cents IS NOT NULL)", UUID.class);
    }

    private void write(String sql, UUID userId, UUID goalId, Competence month, Money value, Instant capturedAt) {
        Timestamp stamp = Timestamp.from(capturedAt);
        jdbc.update(sql, UUID.randomUUID(), userId, goalId, java.sql.Date.valueOf(month.firstDay()), value.cents(), stamp, stamp, stamp);
    }
}
