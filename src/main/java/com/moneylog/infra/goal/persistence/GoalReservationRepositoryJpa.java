package com.moneylog.infra.goal.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalReservationRepositoryJpa extends JpaRepository<GoalReservationEntity, UUID> {

    List<GoalReservationEntity> findByUserId(UUID userId);

    List<GoalReservationEntity> findByUserIdAndGoalIdIn(UUID userId, Collection<UUID> goalIds);
}
