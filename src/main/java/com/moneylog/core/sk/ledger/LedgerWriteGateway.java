package com.moneylog.core.sk.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LedgerWriteGateway {

    void append(UUID userId, List<LedgerEntry> entries);

    int deactivateBySource(UUID userId, PostingSourceType sourceType, UUID sourceId, String reason, Instant at);

    int deactivateByInstallments(UUID userId, UUID transactionId, java.util.Collection<UUID> installmentIds, String reason, Instant at);
}
