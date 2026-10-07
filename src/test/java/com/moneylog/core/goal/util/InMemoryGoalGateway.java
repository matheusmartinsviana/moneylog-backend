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

/**
 * Adaptador em memória da porta {@link GoalGateway}, só para teste (a implementação JPA é {@code GoalGatewayImpl}). Cada gravação sobe a
 * versão, como a persistência real; guarda <b>uma cópia</b> da meta, então mudar o objeto devolvido sem gravar não altera o que está guardado.
 */
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

    private static Goal copy(Goal g) {
        return Goal.restore(g.id(), g.userId(), g.name(), g.target(), g.deadline(), g.priority(), g.description(), g.isArchived(), g.kind(), g.baseline(), g.baselineAt(), g.reservations());
    }
}
