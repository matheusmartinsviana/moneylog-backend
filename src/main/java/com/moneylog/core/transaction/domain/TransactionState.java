package com.moneylog.core.transaction.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public record TransactionState(UUID id, UUID userId, TransactionType type, Money amount, LocalDate date, String description,
                               String notes, TransactionOrigin origin, PaymentMethod paymentMethod, UUID accountId,
                               UUID counterpartAccountId, UUID cardId, UUID categoryId, UUID debtId, UUID refundOfId,
                               InvestmentDirection investmentDirection, AdjustmentDirection adjustmentDirection,
                               AdjustmentKind adjustmentKind, String reason, LocalDate invoiceClosingDate, boolean lateEntry,
                               List<TransactionInstallment> installments, TransactionStatus status, String voidReason,
                               Instant voidedAt, long version, Competence incomeCompetence) {

    public TransactionState {
        installments = installments == null ? List.of() : List.copyOf(installments);
    }

    public TransactionState(UUID id, UUID userId, TransactionType type, Money amount, LocalDate date, String description,
                            String notes, TransactionOrigin origin, PaymentMethod paymentMethod, UUID accountId,
                            UUID counterpartAccountId, UUID cardId, UUID categoryId, UUID debtId, UUID refundOfId,
                            InvestmentDirection investmentDirection, AdjustmentDirection adjustmentDirection,
                            AdjustmentKind adjustmentKind, String reason, LocalDate invoiceClosingDate, boolean lateEntry,
                            List<TransactionInstallment> installments, TransactionStatus status, String voidReason,
                            Instant voidedAt, long version) {
        this(id, userId, type, amount, date, description, notes, origin, paymentMethod, accountId, counterpartAccountId, cardId,
                categoryId, debtId, refundOfId, investmentDirection, adjustmentDirection, adjustmentKind, reason,
                invoiceClosingDate, lateEntry, installments, status, voidReason, voidedAt, version, null);
    }
}
