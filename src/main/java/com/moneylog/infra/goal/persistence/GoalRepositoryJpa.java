package com.moneylog.infra.goal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GoalRepositoryJpa extends JpaRepository<GoalEntity, UUID> {

    Optional<GoalEntity> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from GoalEntity g where g.id = :id and g.userId = :userId")
    Optional<GoalEntity> lockByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    List<GoalEntity> findByUserId(UUID userId);

    Optional<GoalEntity> findByUserIdAndFeaturedTrue(UUID userId);
}
