package com.moneylog.infra.quickentry.gateways;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;

import com.moneylog.core.quickentry.app.gateways.QuickEntryAuditGateway;
import com.moneylog.core.quickentry.domain.QuickEntryAudit;
import com.moneylog.infra.quickentry.persistence.QuickEntryAuditEntity;
import com.moneylog.infra.quickentry.persistence.QuickEntryAuditRepositoryJpa;

public class QuickEntryAuditGatewayImpl implements QuickEntryAuditGateway {

    private final QuickEntryAuditRepositoryJpa repository;

    public QuickEntryAuditGatewayImpl(QuickEntryAuditRepositoryJpa repository) {
        this.repository = repository;
    }

    @Override
    public void insert(QuickEntryAudit audit) {
        repository.saveAndFlush(new QuickEntryAuditEntity(audit.transactionId(), audit.tokenId(), audit.userId(), audit.ip(),
                audit.createdAt()));
    }

    @Override
    public Optional<QuickEntryAudit> findByTransactionId(UUID userId, UUID transactionId) {
        return repository.findByUserIdAndTransactionId(userId, transactionId).map(QuickEntryAuditGatewayImpl::toDomain);
    }

    @Override
    public List<QuickEntryAudit> listByToken(UUID userId, UUID tokenId, int limit) {
        return repository.findByUserIdAndTokenIdOrderByCreatedAtDescTransactionIdAsc(userId, tokenId, PageRequest.of(0, limit)).stream()
                .map(QuickEntryAuditGatewayImpl::toDomain).toList();
    }

    private static QuickEntryAudit toDomain(QuickEntryAuditEntity e) {
        return new QuickEntryAudit(e.getTransactionId(), e.getTokenId(), e.getUserId(), e.getIp(), e.getCreatedAt());
    }
}
