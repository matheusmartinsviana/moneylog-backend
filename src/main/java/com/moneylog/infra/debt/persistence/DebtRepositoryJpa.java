package com.moneylog.infra.debt.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DebtRepositoryJpa extends JpaRepository<DebtEntity, UUID> {

    Optional<DebtEntity> findByIdAndUserId(UUID id, UUID userId);

    /** {@code SELECT ... FOR UPDATE} da linha: quitações, perdão e estorno da mesma dívida se serializam. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DebtEntity d where d.id = :id and d.userId = :userId")
    Optional<DebtEntity> lockByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    List<DebtEntity> findByUserId(UUID userId);

    /** Só os ids (sem carregar entidades): usado para travar antes de ler. */
    @Query("select d.id from DebtEntity d where d.userId = :userId and d.originTransactionId = :transactionId order by d.id")
    List<UUID> findIdsByOrigin(@Param("userId") UUID userId, @Param("transactionId") UUID transactionId);

    List<DebtEntity> findByUserIdAndPersonIdIn(UUID userId, Collection<UUID> personIds);

    List<DebtEntity> findByUserIdAndOriginTransactionId(UUID userId, UUID originTransactionId);

    List<DebtEntity> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);
}
