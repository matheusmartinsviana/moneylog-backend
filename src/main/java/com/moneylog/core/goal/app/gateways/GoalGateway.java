package com.moneylog.core.goal.app.gateways;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.sk.persistence.Versioned;

public interface GoalGateway {

    Versioned<Goal> save(Goal goal);

    void lockForUpdate(UUID userId, UUID goalId);

    Optional<Versioned<Goal>> findById(UUID userId, UUID goalId);

    List<Versioned<Goal>> findAll(UUID userId);

    Optional<Versioned<Goal>> findFeatured(UUID userId);

    void lockUserMedia(UUID userId);

    PhotoUsage photoUsage(UUID userId);

    record PhotoUsage(int count, long bytes) {
    }
}
