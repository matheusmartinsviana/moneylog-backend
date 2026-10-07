package com.moneylog.infra.transaction.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InstallmentRepositoryJpa extends JpaRepository<InstallmentEntity, UUID> {

    List<InstallmentEntity> findByUserId(UUID userId);

    List<InstallmentEntity> findByUserIdAndTransactionIdIn(UUID userId, Collection<UUID> transactionIds);
}
