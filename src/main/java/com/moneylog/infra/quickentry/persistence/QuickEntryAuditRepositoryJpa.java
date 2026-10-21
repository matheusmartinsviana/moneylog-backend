package com.moneylog.infra.quickentry.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuickEntryAuditRepositoryJpa extends JpaRepository<QuickEntryAuditEntity, UUID> {

    Optional<QuickEntryAuditEntity> findByUserIdAndTransactionId(UUID userId, UUID transactionId);

    List<QuickEntryAuditEntity> findByUserIdAndTokenIdOrderByCreatedAtDescTransactionIdAsc(UUID userId, UUID tokenId, Pageable page);
}
