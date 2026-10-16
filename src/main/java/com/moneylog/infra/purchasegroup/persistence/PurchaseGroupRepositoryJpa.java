package com.moneylog.infra.purchasegroup.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseGroupRepositoryJpa extends JpaRepository<PurchaseGroupEntity, UUID> {

    Optional<PurchaseGroupEntity> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from PurchaseGroupEntity g where g.id = :id and g.userId = :userId")
    Optional<PurchaseGroupEntity> lockByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("select g from PurchaseGroupEntity g where g.userId = :userId order by lower(g.name), g.id")
    List<PurchaseGroupEntity> findAllOf(@Param("userId") UUID userId);
}
