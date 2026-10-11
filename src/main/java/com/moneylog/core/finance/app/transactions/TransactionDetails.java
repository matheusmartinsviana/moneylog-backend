package com.moneylog.core.finance.app.transactions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InstallmentStatus;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.debt.app.DebtSummary;
import com.moneylog.core.finance.app.cards.InvoiceView;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.Transaction;

public record TransactionDetails(Transaction transaction, Instant createdAt, Instant updatedAt, Account account,
                                 Account counterpartAccount, Category category, String refundOfDescription, Impact impact,
                                 Flags flags, CardInfo cardInfo, List<DebtSummary> debts) {

    public TransactionDetails {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(impact, "impact");
        Objects.requireNonNull(flags, "flags");
        debts = debts == null ? List.of() : List.copyOf(debts);
    }

    public TransactionDetails(Transaction transaction, Instant createdAt, Instant updatedAt, Account account,
                              Account counterpartAccount, Category category, String refundOfDescription, Impact impact,
                              Flags flags, CardInfo cardInfo) {
        this(transaction, createdAt, updatedAt, account, counterpartAccount, category, refundOfDescription, impact, flags, cardInfo,
                List.of());
    }

    public TransactionDetails(Transaction transaction, Instant createdAt, Instant updatedAt, Account account,
                              Account counterpartAccount, Category category, String refundOfDescription, Impact impact,
                              Flags flags) {
        this(transaction, createdAt, updatedAt, account, counterpartAccount, category, refundOfDescription, impact, flags, null,
                List.of());
    }

    public record ConsumptionImpact(Competence competence, Money amount) {
    }

    public record Impact(Money cash, List<ConsumptionImpact> consumption, Money netWorth, Money commitment) {
    }

    public record Flags(boolean editable, boolean voidable, VoidBlockedBy voidBlockedBy) {
    }

    public record InstallmentView(int number, int total, Money amount, Competence competence, UUID invoiceId,
                                  java.time.YearMonth invoiceReferenceMonth, LocalDate invoiceDueDate, InstallmentStatus status,
                                  boolean lateEntry) {
    }

    public record CardInfo(CreditCard card, List<InstallmentView> installments, InvoiceView invoice) {
        public CardInfo {
            installments = installments == null ? List.of() : List.copyOf(installments);
        }
    }
}
