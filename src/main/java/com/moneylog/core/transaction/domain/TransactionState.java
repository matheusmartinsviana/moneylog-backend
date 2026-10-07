package com.moneylog.core.transaction.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.sk.money.Money;

/**
 * Estado persistido de uma {@link Transaction}, sem os lançamentos do razão (que vão à parte, como
 * {@code LedgerEntry}). É o que o mapper da infra grava ({@link Transaction#state()}) e devolve a
 * {@link Transaction#restore(TransactionState, List)}.
 *
 * @param version versão do lock otimista (cópia do que está gravada; quem incrementa é a persistência)
 */
public record TransactionState(UUID id, UUID userId, TransactionType type, Money amount, LocalDate date, String description,
                               String notes, TransactionOrigin origin, PaymentMethod paymentMethod, UUID accountId,
                               UUID counterpartAccountId, UUID cardId, UUID categoryId, UUID debtId, UUID refundOfId,
                               InvestmentDirection investmentDirection, AdjustmentDirection adjustmentDirection,
                               AdjustmentKind adjustmentKind, String reason, LocalDate invoiceClosingDate, boolean lateEntry,
                               List<TransactionInstallment> installments, TransactionStatus status, String voidReason,
                               Instant voidedAt, long version) {

    public TransactionState {
        installments = installments == null ? List.of() : List.copyOf(installments);
    }
}
