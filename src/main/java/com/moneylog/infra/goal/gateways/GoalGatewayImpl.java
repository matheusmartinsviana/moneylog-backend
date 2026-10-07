package com.moneylog.infra.goal.gateways;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.goal.domain.Goal;
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

    public GoalGatewayImpl(GoalRepositoryJpa goals, GoalReservationRepositoryJpa reservations, TimeProvider time) {
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
                    goal.baseline() == null ? null : goal.baseline().cents(), goal.baselineAt(), now);
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
        return GoalRepositoryMapper.toVersioned(saved, concat(stored, toInsert));
    }

    @Override
    public void lockForUpdate(UUID userId, UUID goalId) {
        goals.lockByIdAndUserId(goalId, userId);
    }

    @Override
    public Optional<Versioned<Goal>> findById(UUID userId, UUID goalId) {
        return goals.findByIdAndUserId(goalId, userId)
                .map(e -> GoalRepositoryMapper.toVersioned(e, reservations.findByUserIdAndGoalIdIn(userId, List.of(goalId))));
    }

    @Override
    public List<Versioned<Goal>> findAll(UUID userId) {
        List<GoalEntity> entities = goals.findByUserId(userId);
        if (entities.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<GoalReservationEntity>> byGoal = reservations.findByUserId(userId).stream()
                .collect(Collectors.groupingBy(GoalReservationEntity::getGoalId));
        return entities.stream().map(e -> GoalRepositoryMapper.toVersioned(e, byGoal.getOrDefault(e.getId(), List.of()))).toList();
    }

    private static List<GoalReservationEntity> concat(List<GoalReservationEntity> a, List<GoalReservationEntity> b) {
        List<GoalReservationEntity> all = new java.util.ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
