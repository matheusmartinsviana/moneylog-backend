package com.moneylog.infra.income.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncomeProfileRepositoryJpa extends JpaRepository<IncomeProfileEntity, UUID> {

    Optional<IncomeProfileEntity> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from IncomeProfileEntity p where p.id = :id and p.userId = :userId")
    Optional<IncomeProfileEntity> lockByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("""
            select p from IncomeProfileEntity p
            where p.userId = :userId and p.archived = :archived
            order by lower(p.source), p.id""")
    List<IncomeProfileEntity> findPage(@Param("userId") UUID userId, @Param("archived") boolean archived, Pageable page);

    List<IncomeProfileEntity> findByUserIdAndArchivedFalse(UUID userId);

    List<IncomeProfileEntity> findByUserId(UUID userId);
}
