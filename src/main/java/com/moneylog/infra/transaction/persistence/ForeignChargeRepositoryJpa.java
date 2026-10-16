package com.moneylog.infra.transaction.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ForeignChargeRepositoryJpa extends JpaRepository<ForeignChargeEntity, UUID> {

    List<ForeignChargeEntity> findByUserIdAndTransactionIdIn(UUID userId, Collection<UUID> transactionIds);

    List<ForeignChargeEntity> findByUserId(UUID userId);
}
