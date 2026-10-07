package com.moneylog.infra.recurrence.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecurrenceRepositoryJpa extends JpaRepository<RecurrenceEntity, UUID> {

    Optional<RecurrenceEntity> findByIdAndUserId(UUID id, UUID userId);

    /** {@code SELECT ... FOR UPDATE} da linha: duas realizações da mesma ocorrência se serializam aqui. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RecurrenceEntity r where r.id = :id and r.userId = :userId")
    Optional<RecurrenceEntity> lockByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    List<RecurrenceEntity> findByUserId(UUID userId);

    @Query("""
            select r from RecurrenceEntity r
            where r.userId = :userId and (:active is null or r.active = :active)
            order by lower(coalesce(r.description, '')), r.id""")
    List<RecurrenceEntity> findPage(@Param("userId") UUID userId, @Param("active") Boolean active, Pageable page);
}
