package com.moneylog.infra.transaction.gateways;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionStateInconsistentException;
import com.moneylog.infra.card.gateways.InvoiceRowGateway.InvoiceRow;
import com.moneylog.infra.transaction.persistence.InstallmentEntity;

import com.moneylog.core.importing.domain.DedupKey;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.AdjustmentDirection;
import com.moneylog.core.transaction.domain.AdjustmentKind;
import com.moneylog.core.transaction.domain.InvestmentDirection;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.core.transaction.domain.TransactionSource;
import com.moneylog.core.transaction.domain.TransactionState;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.infra.transaction.persistence.TransactionEntity;

public final class TransactionRepositoryMapper {

    private TransactionRepositoryMapper() {
    }

    public static Transaction toDomain(TransactionEntity e, List<LedgerEntry> entries) {
        return toDomain(e, entries, List.of(), Map.of());
    }

    public static Transaction toDomain(TransactionEntity e, List<LedgerEntry> entries, List<InstallmentEntity> installmentRows,
                                       Map<UUID, InvoiceRow> invoices) {
        CurrencyCode currency = CurrencyCode.of(e.getCurrency());
        boolean active = e.getStatus() == TransactionStatus.ACTIVE;
        List<TransactionInstallment> installments = installments(e, currency, installmentRows, invoices);
        Set<UUID> canceled = installments.stream().filter(TransactionInstallment::isCanceled).map(TransactionInstallment::id)
                .collect(Collectors.toSet());
        boolean cardPurchase = e.getType() == TransactionType.CARD_PURCHASE;
        List<LedgerEntry> relevant = entries.stream()
                .filter(entry -> active ? entry.active() || (cardPurchase && canceled.contains(entry.source().installmentId()))
                        : !entry.active())
                .toList();
        InvestmentDirection investment = e.getType() == TransactionType.INVESTMENT && e.getDirection() != null
                ? InvestmentDirection.valueOf(e.getDirection()) : null;
        AdjustmentDirection adjustment = e.getType() == TransactionType.ADJUSTMENT && e.getDirection() != null
                ? AdjustmentDirection.valueOf(e.getDirection()) : null;
        AdjustmentKind kind = e.getAdjustmentKind() == null ? null : AdjustmentKind.valueOf(e.getAdjustmentKind());
        LocalDate invoiceClosingDate = e.getInvoiceId() == null ? null : closingDate(e, e.getInvoiceId(), invoices);
        TransactionState state = new TransactionState(e.getId(), e.getUserId(), e.getType(),
                Money.ofCents(e.getAmountCents(), currency), e.getTransactionDate(), e.getDescription(), e.getNotes(),
                origin(e), e.getPaymentMethod(), e.getAccountId(), e.getCounterpartAccountId(), e.getCardId(), e.getCategoryId(),
                e.getDebtId(), e.getRefundOfId(), investment, adjustment, kind, e.getReason(), invoiceClosingDate, e.isLateEntry(),
                installments, e.getStatus(), e.getVoidReason(), e.getVoidedAt(), e.getVersion(),
                e.getIncomeCompetence() == null ? null : Competence.parse(e.getIncomeCompetence().strip()));
        return Transaction.restore(state, relevant);
    }

    private static List<TransactionInstallment> installments(TransactionEntity e, CurrencyCode currency,
                                                              List<InstallmentEntity> rows, Map<UUID, InvoiceRow> invoices) {
        if (e.getType() != TransactionType.CARD_PURCHASE) {
            if (!rows.isEmpty()) {
                throw new TransactionStateInconsistentException("só a compra no cartão tem parcelas.");
            }
            return List.of();
        }
        if (e.getInstallmentCount() == null || rows.size() != e.getInstallmentCount()) {
            throw new TransactionStateInconsistentException("a compra declara " + e.getInstallmentCount() + " parcelas e há "
                    + rows.size() + " gravadas.");
        }
        try {
            return rows.stream().sorted(Comparator.comparingInt(InstallmentEntity::getNumber))
                    .map(row -> new TransactionInstallment(row.getId(), row.getTransactionId(), row.getNumber(),
                            row.getTotalInstallments(), Money.ofCents(row.getAmountCents(), currency), row.getEffectiveDate(),
                            closingDate(e, row.getInvoiceId(), invoices), row.isLateEntry(), row.getCanceledAt()))
                    .toList();
        } catch (DomainException ex) {
            throw new TransactionStateInconsistentException(ex.getMessage(), ex);
        }
    }

    private static LocalDate closingDate(TransactionEntity e, UUID invoiceId, Map<UUID, InvoiceRow> invoices) {
        InvoiceRow row = invoices.get(invoiceId);
        if (row == null) {
            throw new TransactionStateInconsistentException("a fatura " + invoiceId + " não existe.");
        }
        if (!row.cardId().equals(e.getCardId())) {
            throw new TransactionStateInconsistentException("a fatura " + invoiceId + " é de outro cartão.");
        }
        return row.closingDate();
    }

    public static TransactionEntity toEntity(Transaction transaction, Instant now, UUID invoiceId) {
        TransactionState s = transaction.state();
        TransactionOrigin origin = s.origin();
        String direction = s.investmentDirection() != null ? s.investmentDirection().name()
                : s.adjustmentDirection() == null ? null : s.adjustmentDirection().name();
        return new TransactionEntity(s.id(), s.userId(), s.type(), s.status(), s.amount().cents(), s.amount().currency().value(),
                s.date(), s.description(), s.notes(), s.paymentMethod(), s.accountId(), s.counterpartAccountId(), s.cardId(),
                invoiceId, s.lateEntry(), s.installments().isEmpty() ? null : s.installments().size(), s.categoryId(), s.refundOfId(), s.debtId(), direction, s.adjustmentKind() == null ? null : s.adjustmentKind().name(), s.reason(),
                origin.source(), origin.importBatchId(), origin.dedupKey() == null ? null : origin.dedupKey().value(),
                origin.originalDedupKey() == null ? null : origin.originalDedupKey().value(), origin.recurrenceId(),
                origin.occurrenceDate(), s.voidReason(), s.voidedAt(),
                s.incomeCompetence() == null ? null : s.incomeCompetence().toString(), now);
    }

    private static TransactionOrigin origin(TransactionEntity e) {
        if (e.getSource() == TransactionSource.IMPORT) {
            return new TransactionOrigin(TransactionSource.IMPORT, e.getImportBatchId(),
                    e.getDedupKey() == null ? null : new DedupKey(e.getDedupKey()),
                    e.getOriginalDedupKey() == null ? null : new DedupKey(e.getOriginalDedupKey()), null, null);
        }
        if (e.getSource() == TransactionSource.RECURRENCE) {
            return TransactionOrigin.recurrence(e.getRecurrenceId(), e.getOccurrenceDate());
        }
        if (e.getSource() == TransactionSource.ONGOING) {
            return TransactionOrigin.ongoing();
        }
        if (e.getSource() == TransactionSource.HISTORICAL) {
            return TransactionOrigin.historical();
        }
        return TransactionOrigin.manual();
    }
}
