package com.moneylog.core.finance.app.gateways;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSourceType;

public interface LedgerWriteGateway {

    void append(UUID userId, List<LedgerEntry> entries);

    int deactivateBySource(UUID userId, PostingSourceType sourceType, UUID sourceId, String reason, Instant at);

    int deactivateByInstallments(UUID userId, UUID transactionId, java.util.Collection<UUID> installmentIds, String reason, Instant at);
}
