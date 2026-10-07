package com.moneylog.infra.ledger.gateways;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.finance.app.gateways.LedgerReadGateway;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.infra.ledger.persistence.LedgerPostingEntity;
import com.moneylog.infra.ledger.persistence.LedgerPostingRepositoryJpa;

public class LedgerReadGatewayImpl implements LedgerReadGateway {

    private final LedgerPostingRepositoryJpa repository;

    public LedgerReadGatewayImpl(LedgerPostingRepositoryJpa repository) {
        this.repository = repository;
    }

    @Override
    public Map<PostingSource, List<LedgerEntry>> activeEntries(UUID userId) {
        Map<PostingSource, List<LedgerEntry>> bySource = new LinkedHashMap<>();
        for (LedgerPostingEntity entity : repository.findActive(userId)) {
            LedgerEntry entry = LedgerRepositoryMapper.toDomain(entity);
            bySource.computeIfAbsent(entry.source(), key -> new ArrayList<>()).add(entry);
        }
        return bySource;
    }

    @Override
    public Map<UUID, List<LedgerEntry>> entriesOfTransactions(UUID userId, Collection<UUID> transactionIds) {
        Map<UUID, List<LedgerEntry>> byTransaction = new LinkedHashMap<>();
        if (transactionIds.isEmpty()) {
            return byTransaction;
        }
        for (LedgerPostingEntity entity : repository.findByTransactions(userId, transactionIds)) {
            byTransaction.computeIfAbsent(entity.getTransactionId(), key -> new ArrayList<>())
                    .add(LedgerRepositoryMapper.toDomain(entity));
        }
        return byTransaction;
    }
}
