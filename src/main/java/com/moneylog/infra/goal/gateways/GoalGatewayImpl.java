package com.moneylog.infra.goal.gateways;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalAction;
import com.moneylog.core.goal.domain.GoalActionFrequency;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.goal.persistence.GoalEntity;
import com.moneylog.infra.goal.persistence.GoalRepositoryJpa;
import com.moneylog.infra.goal.persistence.GoalReservationEntity;
import com.moneylog.infra.goal.persistence.GoalReservationRepositoryJpa;

/** {@link GoalGateway} sobre JPA. As reservas só são inseridas; a meta sobe de versão a cada gravação. */
public class GoalGatewayImpl implements GoalGateway {

    private final GoalRepositoryJpa goals;
    private final GoalReservationRepositoryJpa reservations;
    private final TimeProvider time;
    private final JdbcTemplate jdbc;

    public GoalGatewayImpl(GoalRepositoryJpa goals, GoalReservationRepositoryJpa reservations, TimeProvider time, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.goals = goals;
        this.reservations = reservations;
        this.time = time;
    }

    @Override
    public Versioned<Goal> save(Goal goal) {
        GoalEntity entity = goals.findByIdAndUserId(goal.id(), goal.userId()).orElse(null);
        java.time.Instant now = time.now();
        if (entity == null) {
            entity = GoalRepositoryMapper.toEntity(goal, now);
        } else {
            entity.apply(goal.name(), goal.target().cents(), goal.deadline(), goal.priority(), goal.description(), goal.isArchived(),
                    goal.baseline() == null ? null : goal.baseline().cents(), goal.baselineAt(), goal.direction(), goal.planNote(), now);
        }
        GoalEntity saved = goals.saveAndFlush(entity);
        List<GoalReservationEntity> stored = reservations.findByUserIdAndGoalIdIn(goal.userId(), List.of(goal.id()));
        Set<UUID> storedIds = new HashSet<>();
        stored.forEach(r -> storedIds.add(r.getId()));
        List<GoalReservationEntity> toInsert = goal.reservations().stream().filter(r -> !storedIds.contains(r.id()))
                .map(r -> GoalRepositoryMapper.toEntity(r, goal.userId())).toList();
        if (!toInsert.isEmpty()) {
            reservations.saveAllAndFlush(toInsert);
        }
        syncActions(goal, now);
        return GoalRepositoryMapper.toVersioned(saved, concat(stored, toInsert), goal.actions());
    }

    @Override
    public void lockForUpdate(UUID userId, UUID goalId) {
        goals.lockByIdAndUserId(goalId, userId);
    }

    @Override
    public Optional<Versioned<Goal>> findById(UUID userId, UUID goalId) {
        return goals.findByIdAndUserId(goalId, userId)
                .map(e -> GoalRepositoryMapper.toVersioned(e, reservations.findByUserIdAndGoalIdIn(userId, List.of(goalId)),
                        loadActions(userId, goalId).getOrDefault(goalId, List.of())));
    }

    @Override
    public List<Versioned<Goal>> findAll(UUID userId) {
        List<GoalEntity> entities = goals.findByUserId(userId);
        if (entities.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<GoalReservationEntity>> byGoal = reservations.findByUserId(userId).stream()
                .collect(Collectors.groupingBy(GoalReservationEntity::getGoalId));
        Map<UUID, List<GoalAction>> actions = loadActions(userId, null);
        return entities.stream().map(e -> GoalRepositoryMapper.toVersioned(e, byGoal.getOrDefault(e.getId(), List.of()),
                actions.getOrDefault(e.getId(), List.of()))).toList();
    }

    // ------------------------------------------------------------------ plano declarado (ações e check-ins)

    private Map<UUID, List<GoalAction>> loadActions(UUID userId, UUID goalId) {
        Map<UUID, Set<String>> done = new java.util.HashMap<>();
        jdbc.query("SELECT c.action_id, c.period FROM goal_action_checkins c JOIN goal_actions a ON a.id = c.action_id AND a.user_id = c.user_id"
                + " WHERE c.user_id = ?" + (goalId == null ? "" : " AND a.goal_id = ?"), rs -> {
            done.computeIfAbsent(rs.getObject(1, UUID.class), k -> new TreeSet<>()).add(rs.getString(2));
        }, goalId == null ? new Object[] {userId} : new Object[] {userId, goalId});
        Map<UUID, List<GoalAction>> byGoal = new java.util.HashMap<>();
        jdbc.query("SELECT id, goal_id, title, frequency, created_at FROM goal_actions WHERE user_id = ?" + (goalId == null ? "" : " AND goal_id = ?")
                + " ORDER BY goal_id, position", rs -> {
            UUID id = rs.getObject(1, UUID.class);
            byGoal.computeIfAbsent(rs.getObject(2, UUID.class), k -> new ArrayList<>()).add(new GoalAction(id, rs.getString(3),
                    GoalActionFrequency.valueOf(rs.getString(4)), rs.getObject(5, OffsetDateTime.class).toInstant(),
                    done.getOrDefault(id, Set.of())));
        }, goalId == null ? new Object[] {userId} : new Object[] {userId, goalId});
        return byGoal;
    }

    /** Regrava as ações e os check-ins só se mudaram (as tabelas são filhas da meta; a ordem é a da lista). */
    private void syncActions(Goal goal, java.time.Instant now) {
        List<GoalAction> stored = loadActions(goal.userId(), goal.id()).getOrDefault(goal.id(), List.of());
        if (stored.equals(goal.actions())) {
            return;
        }
        jdbc.update("DELETE FROM goal_action_checkins WHERE user_id = ? AND action_id IN (SELECT id FROM goal_actions WHERE goal_id = ? AND user_id = ?)",
                goal.userId(), goal.id(), goal.userId());
        jdbc.update("DELETE FROM goal_actions WHERE goal_id = ? AND user_id = ?", goal.id(), goal.userId());
        Timestamp stamp = Timestamp.from(now);
        int position = 0;
        for (GoalAction action : goal.actions()) {
            jdbc.update("INSERT INTO goal_actions (id, user_id, goal_id, position, title, frequency, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    action.id(), goal.userId(), goal.id(), position++, action.title(), action.frequency().name(),
                    OffsetDateTime.ofInstant(action.createdAt(), ZoneOffset.UTC), stamp);
            for (String period : action.doneIn()) {
                jdbc.update("INSERT INTO goal_action_checkins (id, user_id, action_id, period, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID(), goal.userId(), action.id(), period, stamp, stamp);
            }
        }
    }

    private static List<GoalReservationEntity> concat(List<GoalReservationEntity> a, List<GoalReservationEntity> b) {
        List<GoalReservationEntity> all = new java.util.ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
