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
import com.moneylog.core.goal.domain.GoalPhoto;
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
                    goal.baseline() == null ? null : goal.baseline().cents(), goal.baselineAt(), goal.direction(), goal.planNote(), goal.isFeatured(), now);
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
        syncPhotos(goal, now);
        return GoalRepositoryMapper.toVersioned(saved, concat(stored, toInsert), goal.actions(), goal.photos());
    }

    @Override
    public void lockForUpdate(UUID userId, UUID goalId) {
        goals.lockByIdAndUserId(goalId, userId);
    }

    @Override
    public Optional<Versioned<Goal>> findById(UUID userId, UUID goalId) {
        return goals.findByIdAndUserId(goalId, userId)
                .map(e -> GoalRepositoryMapper.toVersioned(e, reservations.findByUserIdAndGoalIdIn(userId, List.of(goalId)),
                        loadActions(userId, goalId).getOrDefault(goalId, List.of()),
                        loadPhotos(userId, goalId).getOrDefault(goalId, List.of())));
    }

    @Override
    public Optional<Versioned<Goal>> findFeatured(UUID userId) {
        return goals.findByUserIdAndFeaturedTrue(userId).map(e -> findById(userId, e.getId()).orElseThrow());
    }

    @Override
    public void lockUserMedia(UUID userId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                "goal-media:" + userId);
    }

    @Override
    public PhotoUsage photoUsage(UUID userId) {
        return jdbc.queryForObject("SELECT count(*), COALESCE(sum(size_bytes), 0) FROM goal_photos WHERE user_id = ?",
                (rs, row) -> new PhotoUsage(rs.getInt(1), rs.getLong(2)), userId);
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
        Map<UUID, List<GoalPhoto>> photos = loadPhotos(userId, null);
        return entities.stream().map(e -> GoalRepositoryMapper.toVersioned(e, byGoal.getOrDefault(e.getId(), List.of()),
                actions.getOrDefault(e.getId(), List.of()), photos.getOrDefault(e.getId(), List.of()))).toList();
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

    // ------------------------------------------------------------------ fotos (só metadados; os bytes ficam em PhotoStorage)

    private Map<UUID, List<GoalPhoto>> loadPhotos(UUID userId, UUID goalId) {
        Map<UUID, List<GoalPhoto>> byGoal = new java.util.HashMap<>();
        jdbc.query("SELECT id, goal_id, width, height, size_bytes, created_at FROM goal_photos WHERE user_id = ?"
                + (goalId == null ? "" : " AND goal_id = ?") + " ORDER BY goal_id, position", rs -> {
            byGoal.computeIfAbsent(rs.getObject(2, UUID.class), k -> new ArrayList<>()).add(new GoalPhoto(rs.getObject(1, UUID.class),
                    rs.getInt(3), rs.getInt(4), rs.getLong(5), rs.getObject(6, OffsetDateTime.class).toInstant()));
        }, goalId == null ? new Object[] {userId} : new Object[] {userId, goalId});
        return byGoal;
    }

    /**
     * Alinha {@code goal_photos} com a lista do agregado: apaga as que saíram (o {@code ON DELETE CASCADE} leva os bytes), insere as novas e
     * regrava a posição das que mudaram. A unicidade de posição é adiada ao fim da transação, então trocar posições não colide.
     */
    private void syncPhotos(Goal goal, java.time.Instant now) {
        Map<UUID, Integer> stored = new java.util.HashMap<>();
        jdbc.query("SELECT id, position FROM goal_photos WHERE goal_id = ? AND user_id = ?",
                rs -> {
                    stored.put(rs.getObject(1, UUID.class), rs.getInt(2));
                }, goal.id(), goal.userId());
        List<GoalPhoto> photos = goal.photos();
        Set<UUID> keep = photos.stream().map(GoalPhoto::id).collect(Collectors.toSet());
        for (UUID id : stored.keySet()) {
            if (!keep.contains(id)) {
                jdbc.update("DELETE FROM goal_photos WHERE id = ? AND user_id = ?", id, goal.userId());
            }
        }
        Timestamp stamp = Timestamp.from(now);
        for (int position = 0; position < photos.size(); position++) {
            GoalPhoto photo = photos.get(position);
            Integer current = stored.get(photo.id());
            if (current == null) {
                jdbc.update("INSERT INTO goal_photos (id, user_id, goal_id, position, width, height, size_bytes, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", photo.id(), goal.userId(), goal.id(), position, photo.width(), photo.height(),
                        photo.sizeBytes(), OffsetDateTime.ofInstant(photo.createdAt(), ZoneOffset.UTC), stamp);
            } else if (current != position) {
                jdbc.update("UPDATE goal_photos SET position = ?, updated_at = ? WHERE id = ? AND user_id = ?", position, stamp, photo.id(),
                        goal.userId());
            }
        }
    }

    private static List<GoalReservationEntity> concat(List<GoalReservationEntity> a, List<GoalReservationEntity> b) {
        List<GoalReservationEntity> all = new java.util.ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
