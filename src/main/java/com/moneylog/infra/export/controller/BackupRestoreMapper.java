package com.moneylog.infra.export.controller;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.asset.domain.BalancePoint;
import com.moneylog.core.card.domain.BillingRule;
import com.moneylog.core.card.domain.CardProfile;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.commitment.domain.CommitmentClauses;
import com.moneylog.core.commitment.domain.ItemShare;
import com.moneylog.core.commitment.domain.ScheduleItem;
import com.moneylog.core.commitment.domain.TrackedCommitment;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtSettlement;
import com.moneylog.core.debt.domain.DebtState;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.finance.app.export.BackupExceptions.BackupInvalidException;
import com.moneylog.core.finance.app.export.BackupExtras;
import com.moneylog.core.finance.app.export.BackupPurchaseGroup;
import com.moneylog.core.finance.app.export.BackupSnapshot;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalAction;
import com.moneylog.core.goal.domain.GoalKind;
import com.moneylog.core.goal.domain.GoalReservation;
import com.moneylog.core.income.app.PayrollLoanApplication;
import com.moneylog.core.income.app.PayrollLoanPlan;
import com.moneylog.core.income.app.PayrollLoanPlanLink;
import com.moneylog.core.income.app.gateways.IncomeProfileGateway.OccurrenceLink;
import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.income.domain.PayrollAdvance;
import com.moneylog.core.income.domain.PayrollDeduction;
import com.moneylog.core.income.domain.ThirteenthSettings;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.Schedule;
import com.moneylog.core.recurrence.domain.SubscriptionTraits;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.provenance.Provenance;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.DayOfMonthRule;
import com.moneylog.core.thirdparty.domain.ThirdPartyExpense;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;
import com.moneylog.core.transaction.domain.ForeignCharge;
import com.moneylog.core.transaction.domain.PurchaseShares;
import com.moneylog.core.transaction.domain.ShareLine;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.core.transaction.domain.TransactionSource;
import com.moneylog.core.transaction.domain.TransactionState;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.infra.export.controller.BackupResponse.AccountItem;
import com.moneylog.infra.export.controller.BackupResponse.AssetItem;
import com.moneylog.infra.export.controller.BackupResponse.CardItem;
import com.moneylog.infra.export.controller.BackupResponse.CategoryItem;
import com.moneylog.infra.export.controller.BackupResponse.CommitmentItem;
import com.moneylog.infra.export.controller.BackupResponse.DebtItem;
import com.moneylog.infra.export.controller.BackupResponse.GoalItem;
import com.moneylog.infra.export.controller.BackupResponse.IncomeProfileItem;
import com.moneylog.infra.export.controller.BackupResponse.PersonItem;
import com.moneylog.infra.export.controller.BackupResponse.PostingItem;
import com.moneylog.infra.export.controller.BackupResponse.ProvenanceItem;
import com.moneylog.infra.export.controller.BackupResponse.PurchaseGroupItem;
import com.moneylog.infra.export.controller.BackupResponse.RecurrenceItem;
import com.moneylog.infra.export.controller.BackupResponse.ScheduleItemItem;
import com.moneylog.infra.export.controller.BackupResponse.SharesItem;
import com.moneylog.infra.export.controller.BackupResponse.TransactionItem;

public final class BackupRestoreMapper {

    private BackupRestoreMapper() {
    }

    public static BackupSnapshot toSnapshot(UUID userId, BackupResponse b) {
        try {
            return build(userId, b);
        } catch (BackupInvalidException e) {
            throw e;
        } catch (DomainException e) {
            throw new BackupInvalidException(e.getMessage(), e);
        } catch (NullPointerException | IllegalArgumentException | DateTimeException | ArithmeticException e) {
            throw new BackupInvalidException("campo obrigatório ausente ou com valor inválido.", e);
        }
    }

    private static BackupSnapshot build(UUID userId, BackupResponse b) {
        Instant generatedAt = Objects.requireNonNull(b.generatedAt(), "generatedAt");
        Map<UUID, String> systemKeys = new HashMap<>();
        List<Category> categories = new ArrayList<>();
        for (CategoryItem c : b.categories()) {
            categories.add(Category.restore(c.id(), userId, c.name(), c.kind(), c.system(), c.archived(), 0L));
            if (c.systemKey() != null) {
                systemKeys.put(c.id(), c.systemKey());
            }
        }
        List<Account> accounts = b.accounts().stream().map(a -> account(userId, a)).toList();
        List<CreditCard> cards = b.cards().stream().map(c -> card(userId, c)).toList();
        List<Person> people = b.people().stream().map(p -> Person.restore(p.id(), userId, p.name(), p.notes(), p.archived())).toList();

        Map<UUID, List<LedgerEntry>> transactionEntries = new HashMap<>();
        Map<UUID, List<LedgerEntry>> debtEntries = new HashMap<>();
        List<LedgerEntry> others = new ArrayList<>();
        Set<UUID> accountIds = new HashSet<>();
        accounts.forEach(a -> accountIds.add(a.id()));
        for (PostingItem p : b.ledgerPostings()) {
            LedgerEntry entry = entry(p);
            switch (p.sourceType()) {
                case TRANSACTION -> transactionEntries.computeIfAbsent(p.sourceId(), k -> new ArrayList<>()).add(entry);
                case DEBT_RECOGNITION, DEBT_CANCELLATION -> debtEntries.computeIfAbsent(p.sourceId(), k -> new ArrayList<>()).add(entry);
                case ACCOUNT_OPENING -> {
                    if (!accountIds.contains(p.sourceId())) {
                        throw new BackupInvalidException("lançamento de abertura de uma conta que não está no arquivo.");
                    }
                }
                default -> others.add(entry);
            }
        }

        List<Transaction> transactions = new ArrayList<>();
        Set<UUID> transactionIds = new HashSet<>();
        for (TransactionItem t : b.transactions()) {
            transactions.add(transaction(userId, t, transactionEntries.remove(t.id()), generatedAt));
            transactionIds.add(t.id());
        }
        List<Debt> debts = new ArrayList<>();
        Set<UUID> debtIds = new HashSet<>();
        for (DebtItem d : b.debts()) {
            debts.add(debt(userId, d, debtEntries.remove(d.id())));
            debtIds.add(d.id());
        }
        if (!transactionEntries.isEmpty()) {
            throw new BackupInvalidException("lançamentos do razão de movimentações que não estão no arquivo.");
        }
        if (!debtEntries.isEmpty()) {
            throw new BackupInvalidException("lançamentos do razão de dívidas que não estão no arquivo.");
        }

        List<RecurringTransaction> recurrences = b.recurrences().stream().map(r -> recurrence(userId, r)).toList();
        List<Goal> goals = b.goals().stream().map(g -> goal(userId, g)).toList();

        Map<UUID, ForeignCharge> foreignCharges = new LinkedHashMap<>();
        for (TransactionItem t : b.transactions()) {
            if (t.originalAmount() != null) {
                foreignCharges.put(t.id(), new ForeignCharge(t.originalAmount(), new BigDecimal(Objects.requireNonNull(t.fxRate(), "fxRate")),
                        t.iofBps() == null ? ForeignCharge.DEFAULT_IOF_BPS : t.iofBps()));
            }
        }

        BackupExtras extras = new BackupExtras(systemKeys, foreignCharges,
                b.purchaseGroups().stream().map(g -> purchaseGroup(userId, g)).toList(),
                b.assets().stream().map(a -> asset(userId, a)).toList(),
                b.incomeProfiles().stream().map(p -> incomeProfile(userId, p)).toList(),
                b.incomeOccurrences().stream().map(o -> new OccurrenceLink(o.profileId(), Competence.parse(o.competence()), o.kind(),
                        o.recurrenceId())).toList(),
                b.payrollLoanPlans().stream().map(p -> new PayrollLoanPlanLink(p.recurrenceId(),
                        new PayrollLoanPlan(p.debtId(), p.applied(), p.interest()))).toList(),
                b.payrollLoanApplications().stream().map(a -> new PayrollLoanApplication(a.transactionId(), a.debtId(), a.applied(),
                        a.interest(), a.principal())).toList(),
                b.commitments().stream().map(c -> commitment(userId, c)).toList(),
                b.thirdPartyExpenses().stream().map(e -> new ThirdPartyExpense(e.id(), userId, e.transactionId(), e.personId(), e.note(),
                        e.amount())).toList(),
                b.thirdPartyReceipts().stream().map(r -> new ThirdPartyReceipt(r.id(), userId, r.personId(), r.transactionId(),
                        r.amount(), r.receivedOn(), r.note(), r.accountId(), r.settlement(), r.ledgered())).toList());

        return new BackupSnapshot(b.schemaVersion(), generatedAt, accounts, categories, cards, transactions, debts, people, recurrences,
                goals, others, extras);
    }

    private static Account account(UUID userId, AccountItem a) {
        return Account.restore(a.id(), userId, a.name(), a.type(), a.institution(), a.openingBalance(), a.openingDate(), a.status(), 0L);
    }

    private static CreditCard card(UUID userId, CardItem c) {
        List<BillingRule> rules = c.billingRules().stream().map(r -> new BillingRule(YearMonth.parse(r.effectiveFrom()),
                DayOfMonthRule.of(r.closingDay()), DayOfMonthRule.of(r.dueDay()))).toList();
        if (rules.isEmpty()) {
            rules = List.of(new BillingRule(YearMonth.of(2000, 1), DayOfMonthRule.of(c.closingDay()), DayOfMonthRule.of(c.dueDay())));
        }
        return CreditCard.restore(c.id(), userId, c.name(), c.institution(), c.limit(), c.defaultPaymentAccountId(), rules, c.status(), 0L,
                c.closingDayConfirmed() == null || c.closingDayConfirmed(),
                new CardProfile(c.autoDebit(), c.brand(), c.bestPurchaseDay(), c.lastDigits()));
    }

    private static LedgerEntry entry(PostingItem p) {
        PostingSource source = new PostingSource(p.sourceType(), p.sourceId(), p.installmentId());
        Competence competence = p.competence() == null ? null : Competence.parse(p.competence());
        return new LedgerEntry(source, new Posting(new BookRef(p.bookType(), p.bookId()), p.amount(), p.effectiveDate(), competence),
                p.active());
    }

    private static PurchaseShares shares(SharesItem item, Function<BackupResponse.ShareLineItem, ShareLine> line) {
        if (item == null) {
            return null;
        }
        return PurchaseShares.restore(item.mode(), item.lines().stream().map(line).toList());
    }

    private static ShareLine shareLine(BackupResponse.ShareLineItem l) {
        return new ShareLine(l.personId(), l.bps(), l.amount(), ProvenanceItem.toDomain(l.provenance()));
    }

    private static Transaction transaction(UUID userId, TransactionItem t, List<LedgerEntry> entries, Instant generatedAt) {
        List<TransactionInstallment> installments = t.installments().stream().map(i -> new TransactionInstallment(i.id(), t.id(),
                i.number(), i.total(), i.amount(), i.effectiveDate(), i.invoiceClosingDate(), i.lateEntry(),
                i.canceled() ? generatedAt : null)).toList();
        TransactionOrigin origin = switch (t.source() == null ? TransactionSource.MANUAL : t.source()) {
            case RECURRENCE -> TransactionOrigin.recurrence(t.recurrenceId(), t.occurrenceDate());
            case ONGOING -> TransactionOrigin.ongoing();
            case HISTORICAL -> TransactionOrigin.historical();
            case MANUAL, IMPORT -> TransactionOrigin.manual();
        };
        TransactionState state = new TransactionState(t.id(), userId, t.type(), t.amount(), t.date(), t.description(), t.notes(), origin,
                t.paymentMethod(), t.accountId(), t.counterpartAccountId(), t.cardId(), t.categoryId(), t.debtId(), t.refundOfId(),
                t.investmentDirection(), t.adjustmentDirection(), t.adjustmentKind(), t.reason(), t.invoiceClosingDate(), t.lateEntry(),
                installments, t.status(), t.voidReason(), t.voidedAt(), 0L,
                t.incomeCompetence() == null ? null : Competence.parse(t.incomeCompetence()), shares(t.shares(), BackupRestoreMapper::shareLine),
                t.type() == TransactionType.EXPENSE ? t.nature() : null);
        Transaction restored = Transaction.restore(state, entries == null ? List.of() : entries);
        restored.attachLastDigits(t.lastDigits());
        return restored;
    }

    private static Debt debt(UUID userId, DebtItem d, List<LedgerEntry> entries) {
        DebtState state = new DebtState(d.id(), userId, d.personId(), d.direction(), d.origin(), d.principal(), d.description(), d.date(),
                d.expectedDate(), d.originTransactionId(), d.closure(), d.closureDate(), d.closureReason(), d.categoryId(),
                d.creditorName(), d.kind(), d.channel());
        List<DebtSettlement> settlements = d.settlements().stream().map(s -> new DebtSettlement(s.transactionId(), s.amount(), s.active()))
                .toList();
        return Debt.restore(state, settlements, entries == null ? List.of() : entries);
    }

    private static RecurringTransaction recurrence(UUID userId, RecurrenceItem r) {
        Schedule schedule = new Schedule(r.frequency(), r.interval(), r.unit(), r.startDate(), r.endDate(), r.maxOccurrences());
        RecurringTransaction recurrence = RecurringTransaction.restore(r.id(), userId, r.type(), r.amount(), r.description(),
                r.categoryId(), r.accountId(), r.cardId(), r.paymentMethod(), schedule, List.of(), r.skippedDates(), r.active(),
                r.incomeCompetenceOffsetMonths(), r.type() == TransactionType.EXPENSE ? r.nature() : null,
                shares(r.shares(), BackupRestoreMapper::shareLine));
        recurrence.defineSubscriptionTraits(new SubscriptionTraits(Boolean.TRUE.equals(r.isSubscription()), r.scope(), r.channel(),
                r.overlapGroup(), r.lastUsedOn()));
        recurrence.defineProvenance(ProvenanceItem.toDomain(r.provenance()));
        if (r.originalAmount() != null) {
            recurrence.defineForeignBasis(new ForeignCharge(r.originalAmount(),
                    new BigDecimal(Objects.requireNonNull(r.referenceFxRate(), "referenceFxRate")),
                    r.iofBps() == null ? ForeignCharge.DEFAULT_IOF_BPS : r.iofBps()));
        }
        return recurrence;
    }

    private static Goal goal(UUID userId, GoalItem g) {
        List<GoalReservation> reservations = g.reservations().stream()
                .map(r -> new GoalReservation(r.id(), g.id(), r.accountId(), r.amount(), r.date(), r.note(), r.createdAt())).toList();
        GoalKind kind = g.kind() == null ? GoalKind.SAVINGS : g.kind();
        Goal goal = Goal.restore(g.id(), userId, g.name(), g.target(), g.deadline(), g.priority(), g.description(), g.archived(), kind,
                g.baseline(), g.baselineAt(), g.metric(), g.direction(), reservations);
        List<GoalAction> actions = g.actions().stream().sorted(Comparator.comparing(BackupResponse.GoalActionItem::createdAt))
                .map(a -> new GoalAction(a.id(), a.title(), a.frequency(), a.createdAt(), Set.copyOf(a.doneIn()))).toList();
        return goal.restorePlan(g.planNote(), actions).restoreMedia(Boolean.TRUE.equals(g.featured()), List.of());
    }

    private static BackupPurchaseGroup purchaseGroup(UUID userId, PurchaseGroupItem g) {
        return new BackupPurchaseGroup(g.id(), userId, g.name(), g.interest(), g.declaredTotal(), g.members());
    }

    private static Asset asset(UUID userId, AssetItem a) {
        List<BalancePoint> history = a.history().stream().map(p -> new BalancePoint(p.balance(), p.date(), p.recordedAt())).toList();
        Asset asset = Asset.restore(a.id(), userId, a.kind(), a.name(), a.employer(), a.balance(), a.balanceDate(), a.monthlyDeposit(),
                a.availability(), a.countsAsReserve(), a.archived(), history);
        asset.defineProvenance(ProvenanceItem.toDomain(a.provenance()));
        return asset;
    }

    private static IncomeProfile incomeProfile(UUID userId, IncomeProfileItem p) {
        PayrollAdvance advance = p.advanceDay() == null ? null : new PayrollAdvance(p.advanceDay(), Objects.requireNonNull(p.advancePercentBps()));
        List<PayrollDeduction> deductions = p.deductions().stream().map(d -> new PayrollDeduction(d.kind(), d.label(), d.amount(),
                d.percentBps(), d.startsOn(), d.endsOn(), d.debtId(), d.interestAmount())).toList();
        IncomeProfile profile = IncomeProfile.restore(p.id(), userId, p.source(), p.gross(), p.dependents(), advance, p.balanceDayOfMonth(),
                deductions, new ThirteenthSettings(p.thirteenthEnabled(), p.thirteenthFirstInstallmentMonth()), p.archived());
        profile.defineProvenance(ProvenanceItem.toDomain(p.provenance()));
        return profile;
    }

    private static TrackedCommitment commitment(UUID userId, CommitmentItem c) {
        List<ScheduleItem> schedule = c.schedule().stream().map(BackupRestoreMapper::scheduleItem).toList();
        CommitmentClauses clauses = c.clauses() == null ? null : new CommitmentClauses(c.clauses().lateFeeBps(),
                c.clauses().monthlyInterestBps(), c.clauses().terminationRetentionBps(), c.clauses().notes());
        return TrackedCommitment.restore(c.id(), userId, c.kind(), c.name(), c.counterparty(), c.notes(), c.total(), c.informedPaid(),
                c.installmentsTotal(), c.installmentsPaid(), c.nextDueDate(), schedule, clauses);
    }

    private static ScheduleItem scheduleItem(ScheduleItemItem i) {
        return new ScheduleItem(i.position(), i.label(), i.type(), i.dueDate(), i.amount(), i.paid(), i.paidOn(),
                i.shares().stream().map(s -> new ItemShare(s.personId(), s.amount())).toList(), i.coBuyerPersonId(),
                ProvenanceItem.toDomain(i.provenance()));
    }
}
