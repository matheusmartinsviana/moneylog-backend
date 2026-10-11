package com.moneylog.core.goal.util;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.sk.persistence.Versioned;

public class InMemoryGoalGateway implements GoalGateway {

    private final Map<UUID, Goal> goals = new HashMap<>();
    private final Map<UUID, Long> versions = new HashMap<>();
    private final Instant now;
    private final List<String> calls = new ArrayList<>();

    public InMemoryGoalGateway(Instant now) {
        this.now = now;
    }

    public List<String> calls() {
        return calls;
    }

    public int saves() {
        return (int) calls.stream().filter("save"::equals).count();
    }

    @Override
    public Versioned<Goal> save(Goal goal) {
        calls.add("save");
        long version = versions.merge(goal.id(), 0L, (old, ignored) -> old + 1);
        goals.put(goal.id(), copy(goal));
        return new Versioned<>(copy(goal), version, now, now);
    }

    @Override
    public void lockForUpdate(UUID userId, UUID goalId) {
        calls.add("lock");
    }

    @Override
    public Optional<Versioned<Goal>> findById(UUID userId, UUID goalId) {
        Goal goal = goals.get(goalId);
        if (goal == null || !goal.userId().equals(userId)) {
            return Optional.empty();
        }
        return Optional.of(new Versioned<>(copy(goal), versions.get(goalId), now, now));
    }

    @Override
    public List<Versioned<Goal>> findAll(UUID userId) {
        return goals.values().stream().filter(g -> g.userId().equals(userId))
                .map(g -> new Versioned<>(copy(g), versions.get(g.id()), now, now)).toList();
    }

    @Override
    public Optional<Versioned<Goal>> findFeatured(UUID userId) {
        return goals.values().stream().filter(g -> g.userId().equals(userId) && g.isFeatured()).findFirst()
                .map(g -> new Versioned<>(copy(g), versions.get(g.id()), now, now));
    }

    @Override
    public void lockUserMedia(UUID userId) {
        calls.add("lockMedia");
    }

    @Override
    public PhotoUsage photoUsage(UUID userId) {
        var photos = goals.values().stream().filter(g -> g.userId().equals(userId)).flatMap(g -> g.photos().stream()).toList();
        return new PhotoUsage(photos.size(), photos.stream().mapToLong(com.moneylog.core.goal.domain.GoalPhoto::sizeBytes).sum());
    }

    private static Goal copy(Goal g) {
        return Goal.restore(g.id(), g.userId(), g.name(), g.target(), g.deadline(), g.priority(), g.description(), g.isArchived(), g.kind(), g.baseline(), g.baselineAt(), g.metric(), g.direction(), g.reservations())
                .restorePlan(g.planNote(), g.actions()).restoreMedia(g.isFeatured(), g.photos());
    }
}
