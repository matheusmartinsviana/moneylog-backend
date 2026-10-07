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
import com.moneylog.infra.transaction.persistence.InstallmentEntity;
import com.moneylog.infra.transaction.persistence.InstallmentRepositoryJpa;
import com.moneylog.infra.transaction.persistence.TransactionEntity;

/**
 * Carrega o que o mapper precisa além da linha da movimentação: as parcelas da compra no cartão e as linhas de fatura
 * que ela e as parcelas referenciam (o domínio fala em {@code closingDate}, a linha em {@code invoice_id}). Sem
 * movimentação de cartão no lote, não faz nenhuma consulta (as leituras da Etapa 2 não mudam).
 */
public class TransactionHydrator {

    /** Parcelas por movimentação e linhas de fatura por id. */
    public record Extras(Map<UUID, List<InstallmentEntity>> installmentsByTransaction, Map<UUID, InvoiceRow> invoices) {

        public static final Extras NONE = new Extras(Map.of(), Map.of());

        public List<InstallmentEntity> installmentsOf(UUID transactionId) {
            return installmentsByTransaction.getOrDefault(transactionId, List.of());
        }
    }

    private final InstallmentRepositoryJpa installments;
    private final InvoiceRowGateway invoices;

    public TransactionHydrator(InstallmentRepositoryJpa installments, InvoiceRowGateway invoices) {
        this.installments = installments;
        this.invoices = invoices;
    }

    /** Extras de um lote de movimentações (por id). */
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
        return new Extras(group(rows), invoices.rowsByIds(userId, invoiceIds));
    }

    /** Extras de todas as movimentações do usuário (carregamento único, S-79). */
    public Extras loadAll(UUID userId) {
        return new Extras(group(installments.findByUserId(userId)), invoices.rowsOfUser(userId));
    }

    public Transaction toDomain(TransactionEntity entity, List<LedgerEntry> entries, Extras extras) {
        return TransactionRepositoryMapper.toDomain(entity, entries, extras.installmentsOf(entity.getId()), extras.invoices());
    }

    private static Map<UUID, List<InstallmentEntity>> group(List<InstallmentEntity> rows) {
        Map<UUID, List<InstallmentEntity>> byTransaction = new HashMap<>();
        for (InstallmentEntity row : rows) {
            byTransaction.computeIfAbsent(row.getTransactionId(), id -> new ArrayList<>()).add(row);
        }
        return byTransaction;
    }
}
