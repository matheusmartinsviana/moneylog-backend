package com.moneylog.infra.recurrence.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecurrenceSkipRepositoryJpa extends JpaRepository<RecurrenceSkipEntity, UUID> {

    List<RecurrenceSkipEntity> findByUserId(UUID userId);

    List<RecurrenceSkipEntity> findByUserIdAndRecurrenceIdIn(UUID userId, Collection<UUID> recurrenceIds);
}
