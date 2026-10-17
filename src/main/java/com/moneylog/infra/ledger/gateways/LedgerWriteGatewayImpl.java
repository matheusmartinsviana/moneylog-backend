package com.moneylog.infra.ledger.gateways;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.ledger.Postings;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.ledger.persistence.LedgerPostingEntity;
import com.moneylog.infra.ledger.persistence.LedgerPostingRepositoryJpa;

public class LedgerWriteGatewayImpl implements LedgerWriteGateway {

    private final LedgerPostingRepositoryJpa repository;
    private final IdGenerator ids;
    private final TimeProvider time;

    public LedgerWriteGatewayImpl(LedgerPostingRepositoryJpa repository, IdGenerator ids, TimeProvider time) {
        this.repository = repository;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public void append(UUID userId, List<LedgerEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        Map<PostingSource, List<Posting>> groups = new LinkedHashMap<>();
        for (LedgerEntry entry : entries) {
            if (!entry.active()) {
                throw new IllegalArgumentException("só lançamentos ativos são gravados; correção é desativar e gravar de novo");
            }
            groups.computeIfAbsent(entry.source(), key -> new ArrayList<>()).add(entry.posting());
        }
        groups.values().forEach(Postings::assertBalanced);
        Instant now = time.now();
        repository.saveAll(entries.stream().map(entry -> LedgerRepositoryMapper.toEntity(ids.next(), userId, entry, now)).toList());
        repository.flush();
    }

    @Override
    public int deactivateBySource(UUID userId, PostingSourceType sourceType, UUID sourceId, String reason, Instant at) {
        return repository.deactivateBySource(userId, sourceType, sourceId, reason, at);
    }

    @Override
    public int deactivateByInstallments(UUID userId, UUID transactionId, java.util.Collection<UUID> installmentIds, String reason,
                                        Instant at) {
        if (installmentIds.isEmpty()) {
            return 0;
        }
        return repository.deactivateByInstallments(userId, transactionId, installmentIds, reason, at);
    }
}
