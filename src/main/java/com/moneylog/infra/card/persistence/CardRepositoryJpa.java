package com.moneylog.infra.card.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.moneylog.core.card.domain.CardStatus;

public interface CardRepositoryJpa extends JpaRepository<CardEntity, UUID> {

    Optional<CardEntity> findByIdAndUserId(UUID id, UUID userId);

    /** {@code SELECT ... FOR UPDATE} da linha do cartão: a trava da cadeia de faturas (ADR-006 §5). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CardEntity c where c.id = :id and c.userId = :userId")
    Optional<CardEntity> lockByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    List<CardEntity> findByUserId(UUID userId);

    @Query("""
            select c from CardEntity c
            where c.userId = :userId and (:status is null or c.status = :status)
            order by lower(c.name), c.id""")
    List<CardEntity> findPage(@Param("userId") UUID userId, @Param("status") CardStatus status, Pageable page);
}
