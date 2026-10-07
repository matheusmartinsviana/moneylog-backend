package com.moneylog.infra.export.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.card.domain.BillingRule;
import com.moneylog.core.card.domain.CardStatus;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtClosure;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.DebtOrigin;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.finance.app.export.BackupSnapshot;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalPriority;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.IntervalUnit;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.AdjustmentDirection;
import com.moneylog.core.transaction.domain.AdjustmentKind;
import com.moneylog.core.transaction.domain.InvestmentDirection;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionSource;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionType;

/**
 * {@code Backup} do contrato (S-153): o JSON versionado ({@code schemaVersion}) com todos os dados financeiros do usuário. Nada de
 * usuário, hash de senha, refresh token nem chave de idempotência: só as entidades de negócio e o razão.
 */
public record BackupResponse(int schemaVersion, Instant generatedAt, List<AccountItem> accounts, List<CategoryItem> categories,
                             List<CardItem> cards, List<TransactionItem> transactions, List<DebtItem> debts, List<PersonItem> people,
                             List<RecurrenceItem> recurrences, List<GoalItem> goals, List<PostingItem> ledgerPostings) {

    public record AccountItem(UUID id, String name, AccountType type, String institution, Money openingBalance, LocalDate openingDate,
                              AccountStatus status) {
    }

    public record CategoryItem(UUID id, String name, CategoryKind kind, boolean system, boolean archived) {
    }

    public record BillingRuleItem(String effectiveFrom, int closingDay, int dueDay) {
    }

    public record CardItem(UUID id, String name, String institution, Money limit, int closingDay, int dueDay, UUID defaultPaymentAccountId,
                           CardStatus status, List<BillingRuleItem> billingRules) {
    }

    public record InstallmentItem(UUID id, int number, int total, Money amount, LocalDate effectiveDate, LocalDate invoiceClosingDate,
                                  boolean lateEntry, boolean canceled) {
    }

    public record TransactionItem(UUID id, TransactionType type, Money amount, LocalDate date, String description, String notes,
                                  TransactionSource source, UUID recurrenceId, LocalDate occurrenceDate, PaymentMethod paymentMethod,
                                  UUID accountId, UUID counterpartAccountId, UUID cardId, UUID categoryId, UUID debtId, UUID refundOfId,
                                  InvestmentDirection investmentDirection, AdjustmentDirection adjustmentDirection,
                                  AdjustmentKind adjustmentKind, String reason, LocalDate invoiceClosingDate, boolean lateEntry,
                                  TransactionStatus status, String voidReason, Instant voidedAt, List<InstallmentItem> installments) {
    }

    public record SettlementItem(UUID transactionId, Money amount, boolean active) {
    }

    public record DebtItem(UUID id, UUID personId, DebtDirection direction, DebtOrigin origin, Money principal, String description,
                           LocalDate date, LocalDate expectedDate, UUID originTransactionId, UUID categoryId, DebtClosure closure,
                           LocalDate closureDate, String closureReason, List<SettlementItem> settlements, String creditorName) {
    }

    public record PersonItem(UUID id, String name, String notes, boolean archived) {
    }

    public record RecurrenceItem(UUID id, TransactionType type, Money amount, String description, UUID categoryId, UUID accountId,
                                 UUID cardId, PaymentMethod paymentMethod, Frequency frequency, int interval, IntervalUnit unit,
                                 LocalDate startDate, LocalDate endDate, Integer maxOccurrences, boolean active,
                                 List<LocalDate> skippedDates) {
    }

    public record ReservationItem(UUID id, UUID accountId, Money amount, LocalDate date, String note, Instant createdAt) {
    }

    public record GoalItem(UUID id, String name, Money target, LocalDate deadline, GoalPriority priority, String description,
                           boolean archived, List<ReservationItem> reservations) {
    }

    public record PostingItem(PostingSourceType sourceType, UUID sourceId, UUID installmentId, BookType bookType, UUID bookId,
                              Money amount, LocalDate effectiveDate, boolean active) {
    }

    public static BackupResponse of(BackupSnapshot s) {
        return new BackupResponse(s.schemaVersion(), s.generatedAt(), s.accounts().stream().map(BackupResponse::account).toList(),
                s.categories().stream().map(BackupResponse::category).toList(), s.cards().stream().map(BackupResponse::card).toList(),
                s.transactions().stream().map(BackupResponse::transaction).toList(), s.debts().stream().map(BackupResponse::debt).toList(),
                s.people().stream().map(BackupResponse::person).toList(),
                s.recurrences().stream().map(BackupResponse::recurrence).toList(), s.goals().stream().map(BackupResponse::goal).toList(),
                s.ledgerPostings().stream().map(BackupResponse::posting).toList());
    }

    private static AccountItem account(Account a) {
        return new AccountItem(a.id(), a.name(), a.type(), a.institution(), a.openingBalance(), a.openingDate(), a.status());
    }

    private static CategoryItem category(Category c) {
        return new CategoryItem(c.id(), c.name(), c.kind(), c.isSystem(), c.isArchived());
    }

    private static CardItem card(CreditCard c) {
        List<BillingRuleItem> rules = c.billingRules().stream().map((BillingRule r) -> new BillingRuleItem(r.effectiveFrom().toString(),
                r.closingDay().day(), r.dueDay().day())).toList();
        return new CardItem(c.id(), c.name(), c.institution(), c.limit(), c.closingDay(), c.dueDay(), c.defaultPaymentAccountId(),
                c.status(), rules);
    }

    private static TransactionItem transaction(Transaction t) {
        List<InstallmentItem> installments = t.installments().stream().map((TransactionInstallment i) -> new InstallmentItem(i.id(),
                i.number(), i.total(), i.amount(), i.effectiveDate(), i.invoiceClosingDate(), i.lateEntry(), i.isCanceled())).toList();
        return new TransactionItem(t.id(), t.type(), t.amount(), t.date(), t.description(), t.notes(), t.origin().source(),
                t.origin().recurrenceId(), t.origin().occurrenceDate(), t.paymentMethod(), t.accountId(), t.counterpartAccountId(),
                t.cardId(), t.categoryId(), t.debtId(), t.refundOfId(), t.investmentDirection(), t.adjustmentDirection(),
                t.adjustmentKind(), t.reason(), t.invoiceClosingDate(), t.lateEntry(), t.status(), t.voidReason(), t.voidedAt(), installments);
    }

    private static DebtItem debt(Debt d) {
        return new DebtItem(d.id(), d.personId(), d.direction(), d.origin(), d.principal(), d.description(), d.date(), d.expectedDate(),
                d.originTransactionId(), d.categoryId(), d.closure(), d.closureDate(), d.closureReason(),
                d.settlements().stream().map(x -> new SettlementItem(x.transactionId(), x.amount(), x.active())).toList(), d.creditorName());
    }

    private static PersonItem person(Person p) {
        return new PersonItem(p.id(), p.name(), p.notes(), p.isArchived());
    }

    private static RecurrenceItem recurrence(RecurringTransaction r) {
        return new RecurrenceItem(r.id(), r.type(), r.amount(), r.description(), r.categoryId(), r.accountId(), r.cardId(),
                r.paymentMethod(), r.schedule().frequency(), r.schedule().interval(), r.schedule().unit(), r.schedule().startDate(),
                r.schedule().endDate(), r.schedule().maxOccurrences(), r.isActive(), r.skippedDates());
    }

    private static GoalItem goal(Goal g) {
        return new GoalItem(g.id(), g.name(), g.target(), g.deadline(), g.priority(), g.description(), g.isArchived(),
                g.reservations().stream().map(r -> new ReservationItem(r.id(), r.accountId(), r.amount(), r.date(), r.note(), r.createdAt()))
                        .toList());
    }

    private static PostingItem posting(LedgerEntry e) {
        return new PostingItem(e.source().type(), e.source().sourceId(), e.source().installmentId(), e.posting().bookType(),
                e.posting().book().id(), e.posting().amount(), e.posting().effectiveDate(), e.active());
    }
}
