package com.moneylog.infra.transaction.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InstallmentStatus;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.TransactionDetails.CardInfo;
import com.moneylog.core.finance.app.transactions.VoidBlockedBy;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionSource;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.infra.card.controller.InvoiceRefResponse;
import com.moneylog.infra.debt.controller.DebtSummaryResponse;

public record TransactionResponse(UUID id, TransactionType type, TransactionStatus status, Money amount, LocalDate date,
                                  String description, String notes, Ref category, Ref card, Ref account, Ref counterpartAccount,
                                  PaymentMethod method, String direction, String adjustmentKind, String reason, RefundOf refundOf,
                                  List<InstallmentResponse> installments, InvoiceRefResponse invoice, ImpactResponse impact,
                                  boolean editable, boolean voidable, VoidBlockedBy voidBlockedBy, String voidReason,
                                  Instant voidedAt, TransactionSource source, long version, Instant createdAt, Instant updatedAt,
                                  DebtSummaryResponse debt, List<DebtSummaryResponse> shares, UUID recurrenceId,
                                  LocalDate occurrenceDate, String incomeCompetence) {

    public record Ref(UUID id, String name) {
    }

    public record RefundOf(UUID id, String description) {
    }

    public record ConsumptionResponse(String competence, Money amount) {
    }

    public record ImpactResponse(Money cash, List<ConsumptionResponse> consumption, Money netWorth, Money commitment) {
    }

    public record InstallmentResponse(int number, int total, Money amount, String competence, UUID invoiceId,
                                      String invoiceReferenceMonth, LocalDate invoiceDueDate, InstallmentStatus status,
                                      boolean lateEntry) {
    }

    public static TransactionResponse of(TransactionDetails d) {
        Transaction t = d.transaction();
        String direction = t.investmentDirection() != null ? t.investmentDirection().name()
                : t.adjustmentDirection() == null ? null : t.adjustmentDirection().name();
        CardInfo cardInfo = d.cardInfo();
        return new TransactionResponse(t.id(), t.type(), t.status(), t.amount(), t.date(), t.description() == null ? "" : t.description(),
                t.notes(), ref(d.category()), cardInfo == null ? null : ref(cardInfo.card()), ref(d.account()),
                ref(d.counterpartAccount()), t.paymentMethod(), direction,
                t.adjustmentKind() == null ? null : t.adjustmentKind().name(), t.reason(),
                t.refundOfId() == null ? null : new RefundOf(t.refundOfId(), d.refundOfDescription() == null ? "" : d.refundOfDescription()),
                cardInfo == null ? List.of() : cardInfo.installments().stream()
                        .map(i -> new InstallmentResponse(i.number(), i.total(), i.amount(), i.competence().toString(), i.invoiceId(),
                                i.invoiceReferenceMonth().toString(), i.invoiceDueDate(), i.status(), i.lateEntry()))
                        .toList(),
                cardInfo == null || cardInfo.invoice() == null ? null : InvoiceRefResponse.of(cardInfo.invoice()),
                new ImpactResponse(d.impact().cash(), d.impact().consumption().stream()
                        .map(c -> new ConsumptionResponse(c.competence().toString(), c.amount())).toList(),
                        d.impact().netWorth(), d.impact().commitment()),
                d.flags().editable(), d.flags().voidable(), d.flags().voidBlockedBy(), t.voidReason(), t.voidedAt(),
                t.origin().source(), t.version(), d.createdAt(), d.updatedAt(),
                t.debtId() != null && !d.debts().isEmpty() ? DebtSummaryResponse.of(d.debts().get(0)) : null,
                t.debtId() == null ? d.debts().stream().map(DebtSummaryResponse::of).toList() : List.of(),
                t.origin().recurrenceId(), t.origin().occurrenceDate(),
                t.incomeCompetence() == null ? null : t.incomeCompetence().toString());
    }

    private static Ref ref(Account account) {
        return account == null ? null : new Ref(account.id(), account.name());
    }

    private static Ref ref(Category category) {
        return category == null ? null : new Ref(category.id(), category.name());
    }

    private static Ref ref(CreditCard card) {
        return new Ref(card.id(), card.name());
    }
}
