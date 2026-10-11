package com.moneylog.core.finance.app.gateways;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSource;

public interface LedgerReadGateway {

    Map<PostingSource, List<LedgerEntry>> activeEntries(UUID userId);

    Map<UUID, List<LedgerEntry>> entriesOfTransactions(UUID userId, Collection<UUID> transactionIds);
}
