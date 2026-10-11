package com.moneylog.core.goal.app.gateways;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.goal.domain.GoalSnapshot;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public interface GoalSnapshotGateway {

    Map<UUID, List<GoalSnapshot>> findByGoals(UUID userId, Collection<UUID> goalIds);

    void upsert(UUID userId, UUID goalId, Competence month, Money value, Instant capturedAt);

    void touchIfAbsent(UUID userId, UUID goalId, Competence month, Money value, Instant capturedAt);

    List<UUID> userIdsWithTrackedGoals();
}
