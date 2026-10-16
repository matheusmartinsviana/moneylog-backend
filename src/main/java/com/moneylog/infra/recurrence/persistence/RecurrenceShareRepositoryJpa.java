package com.moneylog.infra.recurrence.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecurrenceShareRepositoryJpa extends JpaRepository<RecurrenceShareEntity, UUID> {

    List<RecurrenceShareEntity> findByUserId(UUID userId);

    List<RecurrenceShareEntity> findByUserIdAndRecurrenceIdIn(UUID userId, Collection<UUID> recurrenceIds);

    @Modifying(flushAutomatically = true)
    @Query("delete from RecurrenceShareEntity s where s.userId = :userId and s.recurrenceId = :recurrenceId")
    void deleteByRecurrence(@Param("userId") UUID userId, @Param("recurrenceId") UUID recurrenceId);
}
