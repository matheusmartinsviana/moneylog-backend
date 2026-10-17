package com.moneylog.infra.transaction.gateways;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.infra.card.gateways.InvoiceRowGateway;
import com.moneylog.infra.card.gateways.InvoiceRowGateway.InvoiceRow;
import com.moneylog.infra.transaction.persistence.CardPurchaseShareEntity;
import com.moneylog.infra.transaction.persistence.CardPurchaseShareRepositoryJpa;
import com.moneylog.infra.transaction.persistence.InstallmentEntity;
import com.moneylog.infra.transaction.persistence.InstallmentRepositoryJpa;
import com.moneylog.infra.transaction.persistence.TransactionEntity;

public class TransactionHydrator {

    public record Extras(Map<UUID, List<InstallmentEntity>> installmentsByTransaction, Map<UUID, InvoiceRow> invoices,
                         Map<UUID, List<CardPurchaseShareEntity>> sharesByTransaction) {

        public static final Extras NONE = new Extras(Map.of(), Map.of(), Map.of());

        public List<CardPurchaseShareEntity> sharesOf(UUID transactionId) {
            return sharesByTransaction.getOrDefault(transactionId, List.of());
        }

        public List<InstallmentEntity> installmentsOf(UUID transactionId) {
            return installmentsByTransaction.getOrDefault(transactionId, List.of());
        }
    }

    private final InstallmentRepositoryJpa installments;
    private final InvoiceRowGateway invoices;
    private final CardPurchaseShareRepositoryJpa shares;

    public TransactionHydrator(InstallmentRepositoryJpa installments, InvoiceRowGateway invoices, CardPurchaseShareRepositoryJpa shares) {
        this.installments = installments;
        this.invoices = invoices;
        this.shares = shares;
    }

    public Extras loadFor(UUID userId, Collection<TransactionEntity> entities) {
        if (entities.stream().noneMatch(e -> e.getCardId() != null)) {
            return Extras.NONE;
        }
        List<UUID> purchaseIds = entities.stream().filter(e -> e.getType() == TransactionType.CARD_PURCHASE)
                .map(TransactionEntity::getId).toList();
        List<InstallmentEntity> rows = purchaseIds.isEmpty() ? List.of()
                : installments.findByUserIdAndTransactionIdIn(userId, purchaseIds);
        Set<UUID> invoiceIds = new HashSet<>();
        entities.stream().map(TransactionEntity::getInvoiceId).filter(java.util.Objects::nonNull).forEach(invoiceIds::add);
        rows.forEach(row -> invoiceIds.add(row.getInvoiceId()));
        List<CardPurchaseShareEntity> shareRows = purchaseIds.isEmpty() ? List.of()
                : shares.findByUserIdAndTransactionIdIn(userId, purchaseIds);
        return new Extras(group(rows), invoices.rowsByIds(userId, invoiceIds), groupShares(shareRows));
    }

    public Extras loadAll(UUID userId) {
        return new Extras(group(installments.findByUserId(userId)), invoices.rowsOfUser(userId),
                groupShares(shares.findByUserId(userId)));
    }

    public Transaction toDomain(TransactionEntity entity, List<LedgerEntry> entries, Extras extras) {
        return TransactionRepositoryMapper.toDomain(entity, entries, extras.installmentsOf(entity.getId()), extras.invoices(),
                extras.sharesOf(entity.getId()));
    }

    private static Map<UUID, List<CardPurchaseShareEntity>> groupShares(List<CardPurchaseShareEntity> rows) {
        Map<UUID, List<CardPurchaseShareEntity>> byTransaction = new HashMap<>();
        for (CardPurchaseShareEntity row : rows) {
            byTransaction.computeIfAbsent(row.getTransactionId(), id -> new ArrayList<>()).add(row);
        }
        return byTransaction;
    }

    private static Map<UUID, List<InstallmentEntity>> group(List<InstallmentEntity> rows) {
        Map<UUID, List<InstallmentEntity>> byTransaction = new HashMap<>();
        for (InstallmentEntity row : rows) {
            byTransaction.computeIfAbsent(row.getTransactionId(), id -> new ArrayList<>()).add(row);
        }
        return byTransaction;
    }
}
