package com.moneylog.infra.asset.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetRepositoryJpa extends JpaRepository<AssetEntity, UUID> {

    Optional<AssetEntity> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AssetEntity a where a.id = :id and a.userId = :userId")
    Optional<AssetEntity> lockByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("""
            select a from AssetEntity a
            where a.userId = :userId and a.archived = :archived
            order by lower(a.name), a.id""")
    List<AssetEntity> findPage(@Param("userId") UUID userId, @Param("archived") boolean archived, Pageable page);

    List<AssetEntity> findByUserIdAndArchivedFalse(UUID userId);

    List<AssetEntity> findByUserId(UUID userId);
}
