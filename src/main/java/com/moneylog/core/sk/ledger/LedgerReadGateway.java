package com.moneylog.core.sk.ledger;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface LedgerReadGateway {

    Map<PostingSource, List<LedgerEntry>> activeEntries(UUID userId);

    List<LedgerEntry> entriesOfSource(UUID userId, PostingSourceType type, UUID sourceId);

    Map<UUID, List<LedgerEntry>> entriesOfTransactions(UUID userId, Collection<UUID> transactionIds);
}
