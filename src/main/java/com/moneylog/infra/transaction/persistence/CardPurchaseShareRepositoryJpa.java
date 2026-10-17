package com.moneylog.infra.transaction.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardPurchaseShareRepositoryJpa extends JpaRepository<CardPurchaseShareEntity, UUID> {

    List<CardPurchaseShareEntity> findByUserId(UUID userId);

    List<CardPurchaseShareEntity> findByUserIdAndTransactionIdIn(UUID userId, Collection<UUID> transactionIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CardPurchaseShareEntity s where s.userId = :userId and s.transactionId = :transactionId")
    int deleteByTransaction(@Param("userId") UUID userId, @Param("transactionId") UUID transactionId);
}
