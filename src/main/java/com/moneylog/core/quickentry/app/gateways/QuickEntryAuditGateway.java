package com.moneylog.core.quickentry.app.gateways;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.quickentry.domain.QuickEntryAudit;

public interface QuickEntryAuditGateway {

    void insert(QuickEntryAudit audit);

    Optional<QuickEntryAudit> findByTransactionId(UUID userId, UUID transactionId);

    List<QuickEntryAudit> listByToken(UUID userId, UUID tokenId, int limit);
}
