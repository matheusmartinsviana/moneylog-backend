package com.moneylog.infra.export.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.asset.domain.AssetAvailability;
import com.moneylog.core.asset.domain.AssetKind;
import com.moneylog.core.card.domain.BillingRule;
import com.moneylog.core.card.domain.CardStatus;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.commitment.domain.CommitmentClauses;
import com.moneylog.core.commitment.domain.CommitmentKind;
import com.moneylog.core.commitment.domain.ItemShare;
import com.moneylog.core.commitment.domain.ScheduleItem;
import com.moneylog.core.commitment.domain.ScheduleItemType;
import com.moneylog.core.commitment.domain.TrackedCommitment;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtClosure;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.DebtKind;
import com.moneylog.core.debt.domain.DebtOrigin;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.finance.app.export.BackupExtras;
import com.moneylog.core.finance.app.export.BackupPurchaseGroup;
import com.moneylog.core.finance.app.export.BackupSnapshot;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalAction;
import com.moneylog.core.goal.domain.GoalActionFrequency;
import com.moneylog.core.goal.domain.GoalDirection;
import com.moneylog.core.goal.domain.GoalKind;
import com.moneylog.core.goal.domain.GoalMetric;
import com.moneylog.core.goal.domain.GoalPriority;
import com.moneylog.core.income.app.PayrollLoanApplication;
import com.moneylog.core.income.app.PayrollLoanPlanLink;
import com.moneylog.core.income.app.gateways.IncomeProfileGateway.OccurrenceLink;
import com.moneylog.core.income.domain.DeductionKind;
import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.income.domain.PayrollDeduction;
import com.moneylog.core.income.domain.PayrollKind;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.IntervalUnit;
import com.moneylog.core.recurrence.domain.RecurrenceScope;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.SubscriptionTraits;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.provenance.Provenance;
import com.moneylog.core.thirdparty.domain.ReceiptSettlement;
import com.moneylog.core.thirdparty.domain.ThirdPartyExpense;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;
import com.moneylog.core.transaction.domain.AdjustmentDirection;
import com.moneylog.core.transaction.domain.AdjustmentKind;
import com.moneylog.core.transaction.domain.ForeignCharge;
import com.moneylog.core.transaction.domain.InvestmentDirection;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.PurchaseShares;
import com.moneylog.core.transaction.domain.ShareLine;
import com.moneylog.core.transaction.domain.SplitMode;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionNature;
import com.moneylog.core.transaction.domain.TransactionSource;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionType;

public record BackupResponse(int schemaVersion, Instant generatedAt, List<AccountItem> accounts, List<CategoryItem> categories,
                             List<CardItem> cards, List<TransactionItem> transactions, List<DebtItem> debts, List<PersonItem> people,
                             List<RecurrenceItem> recurrences, List<GoalItem> goals, List<PostingItem> ledgerPostings,
                             List<PurchaseGroupItem> purchaseGroups, List<AssetItem> assets, List<IncomeProfileItem> incomeProfiles,
                             List<IncomeOccurrenceItem> incomeOccurrences, List<PayrollLoanPlanItem> payrollLoanPlans,
                             List<PayrollLoanApplicationItem> payrollLoanApplications, List<CommitmentItem> commitments,
                             List<ThirdPartyExpenseItem> thirdPartyExpenses, List<ThirdPartyReceiptItem> thirdPartyReceipts) {

    public BackupResponse {
        accounts = orEmpty(accounts);
        categories = orEmpty(categories);
        cards = orEmpty(cards);
        transactions = orEmpty(transactions);
        debts = orEmpty(debts);
        people = orEmpty(people);
        recurrences = orEmpty(recurrences);
        goals = orEmpty(goals);
        ledgerPostings = orEmpty(ledgerPostings);
        purchaseGroups = orEmpty(purchaseGroups);
        assets = orEmpty(assets);
        incomeProfiles = orEmpty(incomeProfiles);
        incomeOccurrences = orEmpty(incomeOccurrences);
        payrollLoanPlans = orEmpty(payrollLoanPlans);
        payrollLoanApplications = orEmpty(payrollLoanApplications);
        commitments = orEmpty(commitments);
        thirdPartyExpenses = orEmpty(thirdPartyExpenses);
        thirdPartyReceipts = orEmpty(thirdPartyReceipts);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    public record ProvenanceItem(Provenance.Origin origin, Provenance.Confidence confidence) {

        static ProvenanceItem of(Provenance p) {
            return p == null ? null : new ProvenanceItem(p.origin(), p.confidence());
        }

        static Provenance toDomain(ProvenanceItem item) {
            return item == null ? null : new Provenance(item.origin(), item.confidence());
        }
    }

    public record AccountItem(UUID id, String name, AccountType type, String institution, Money openingBalance, LocalDate openingDate,
                              AccountStatus status) {
    }

    public record CategoryItem(UUID id, String name, CategoryKind kind, boolean system, boolean archived, String systemKey) {
    }

    public record BillingRuleItem(String effectiveFrom, int closingDay, int dueDay) {
    }

    public record CardItem(UUID id, String name, String institution, Money limit, int closingDay, int dueDay, UUID defaultPaymentAccountId,
                           CardStatus status, List<BillingRuleItem> billingRules, Boolean closingDayConfirmed, Boolean autoDebit,
                           String brand, Integer bestPurchaseDay, List<String> lastDigits) {

        public CardItem {
            billingRules = billingRules == null ? List.of() : billingRules;
            lastDigits = lastDigits == null ? List.of() : lastDigits;
        }
    }

    public record InstallmentItem(UUID id, int number, int total, Money amount, LocalDate effectiveDate, LocalDate invoiceClosingDate,
                                  boolean lateEntry, boolean canceled) {
    }

    public record ShareLineItem(UUID personId, Integer bps, Money amount, ProvenanceItem provenance) {
    }

    public record SharesItem(SplitMode mode, List<ShareLineItem> lines) {

        public SharesItem {
            lines = lines == null ? List.of() : lines;
        }
    }

    public record TransactionItem(UUID id, TransactionType type, Money amount, LocalDate date, String description, String notes,
                                  TransactionSource source, UUID recurrenceId, LocalDate occurrenceDate, PaymentMethod paymentMethod,
                                  UUID accountId, UUID counterpartAccountId, UUID cardId, UUID categoryId, UUID debtId, UUID refundOfId,
                                  InvestmentDirection investmentDirection, AdjustmentDirection adjustmentDirection,
                                  AdjustmentKind adjustmentKind, String reason, LocalDate invoiceClosingDate, boolean lateEntry,
                                  TransactionStatus status, String voidReason, Instant voidedAt, List<InstallmentItem> installments,
                                  String incomeCompetence, TransactionNature nature, SharesItem shares, Money originalAmount,
                                  String fxRate, Integer iofBps, String lastDigits) {

        public TransactionItem {
            installments = installments == null ? List.of() : installments;
        }
    }

    public record SettlementItem(UUID transactionId, Money amount, boolean active) {
    }

    public record DebtItem(UUID id, UUID personId, DebtDirection direction, DebtOrigin origin, Money principal, String description,
                           LocalDate date, LocalDate expectedDate, UUID originTransactionId, UUID categoryId, DebtClosure closure,
                           LocalDate closureDate, String closureReason, List<SettlementItem> settlements, String creditorName,
                           DebtKind kind, String channel) {

        public DebtItem {
            settlements = settlements == null ? List.of() : settlements;
        }
    }

    public record PersonItem(UUID id, String name, String notes, boolean archived) {
    }

    public record RecurrenceItem(UUID id, TransactionType type, Money amount, String description, UUID categoryId, UUID accountId,
                                 UUID cardId, PaymentMethod paymentMethod, Frequency frequency, int interval, IntervalUnit unit,
                                 LocalDate startDate, LocalDate endDate, Integer maxOccurrences, boolean active,
                                 List<LocalDate> skippedDates, int incomeCompetenceOffsetMonths, TransactionNature nature,
                                 SharesItem shares, Money originalAmount, String referenceFxRate, Integer iofBps,
                                 @JsonProperty("isSubscription") Boolean isSubscription, RecurrenceScope scope, String channel, String overlapGroup,
                                 LocalDate lastUsedOn, ProvenanceItem provenance) {

        public RecurrenceItem {
            skippedDates = skippedDates == null ? List.of() : skippedDates;
        }
    }

    public record ReservationItem(UUID id, UUID accountId, Money amount, LocalDate date, String note, Instant createdAt) {
    }

    public record GoalActionItem(UUID id, String title, GoalActionFrequency frequency, Instant createdAt, List<String> doneIn) {

        public GoalActionItem {
            doneIn = doneIn == null ? List.of() : doneIn;
        }
    }

    public record GoalItem(UUID id, String name, Money target, LocalDate deadline, GoalPriority priority, String description,
                           boolean archived, List<ReservationItem> reservations, GoalKind kind, GoalMetric metric,
                           GoalDirection direction, Money baseline, Instant baselineAt, String planNote, Boolean featured,
                           List<GoalActionItem> actions) {

        public GoalItem {
            reservations = reservations == null ? List.of() : reservations;
            actions = actions == null ? List.of() : actions;
        }
    }

    public record PostingItem(PostingSourceType sourceType, UUID sourceId, UUID installmentId, BookType bookType, UUID bookId,
                              Money amount, LocalDate effectiveDate, boolean active, String competence) {
    }

    public record PurchaseGroupItem(UUID id, String name, boolean interest, Money declaredTotal, List<UUID> members) {

        public PurchaseGroupItem {
            members = members == null ? List.of() : members;
        }
    }

    public record BalancePointItem(Money balance, LocalDate date, Instant recordedAt) {
    }

    public record AssetItem(UUID id, AssetKind kind, String name, String employer, Money balance, LocalDate balanceDate,
                            Money monthlyDeposit, AssetAvailability availability, boolean countsAsReserve, boolean archived,
                            ProvenanceItem provenance, List<BalancePointItem> history) {

        public AssetItem {
            history = history == null ? List.of() : history;
        }
    }

    public record DeductionItem(DeductionKind kind, String label, Money amount, Integer percentBps, LocalDate startsOn,
                                LocalDate endsOn, UUID debtId, Money interestAmount) {
    }

    public record IncomeProfileItem(UUID id, String source, Money gross, int dependents, Integer advanceDay, Integer advancePercentBps,
                                    int balanceDayOfMonth, boolean thirteenthEnabled, int thirteenthFirstInstallmentMonth,
                                    boolean archived, ProvenanceItem provenance, List<DeductionItem> deductions) {

        public IncomeProfileItem {
            deductions = deductions == null ? List.of() : deductions;
        }
    }

    public record IncomeOccurrenceItem(UUID profileId, String competence, PayrollKind kind, UUID recurrenceId) {
    }

    public record PayrollLoanPlanItem(UUID recurrenceId, UUID debtId, Money applied, Money interest) {
    }

    public record PayrollLoanApplicationItem(UUID transactionId, UUID debtId, Money applied, Money interest, Money principal) {
    }

    public record ItemShareItem(UUID personId, Money amount) {
    }

    public record ScheduleItemItem(int position, String label, ScheduleItemType type, LocalDate dueDate, Money amount, boolean paid,
                                   LocalDate paidOn, List<ItemShareItem> shares, UUID coBuyerPersonId, ProvenanceItem provenance) {

        public ScheduleItemItem {
            shares = shares == null ? List.of() : shares;
        }
    }

    public record ClausesItem(int lateFeeBps, int monthlyInterestBps, int terminationRetentionBps, String notes) {
    }

    public record CommitmentItem(UUID id, CommitmentKind kind, String name, String counterparty, String notes, Money total,
                                 Money informedPaid, Integer installmentsTotal, Integer installmentsPaid, LocalDate nextDueDate,
                                 ClausesItem clauses, List<ScheduleItemItem> schedule) {

        public CommitmentItem {
            schedule = schedule == null ? List.of() : schedule;
        }
    }

    public record ThirdPartyExpenseItem(UUID id, UUID transactionId, UUID personId, String note, Money amount) {
    }

    public record ThirdPartyReceiptItem(UUID id, UUID personId, UUID transactionId, Money amount, LocalDate receivedOn, String note,
                                        UUID accountId, ReceiptSettlement settlement, boolean ledgered) {
    }

    public static BackupResponse of(BackupSnapshot s) {
        BackupExtras x = s.extras();
        return new BackupResponse(s.schemaVersion(), s.generatedAt(), s.accounts().stream().map(BackupResponse::account).toList(),
                s.categories().stream().map(c -> category(c, x)).toList(), s.cards().stream().map(BackupResponse::card).toList(),
                s.transactions().stream().map(t -> transaction(t, x)).toList(), s.debts().stream().map(BackupResponse::debt).toList(),
                s.people().stream().map(BackupResponse::person).toList(),
                s.recurrences().stream().map(BackupResponse::recurrence).toList(), s.goals().stream().map(BackupResponse::goal).toList(),
                s.ledgerPostings().stream().map(BackupResponse::posting).toList(),
                x.purchaseGroups().stream().map(BackupResponse::purchaseGroup).toList(),
                x.assets().stream().map(BackupResponse::asset).toList(),
                x.incomeProfiles().stream().map(BackupResponse::incomeProfile).toList(),
                x.incomeOccurrences().stream().map(BackupResponse::incomeOccurrence).toList(),
                x.payrollLoanPlans().stream().map(BackupResponse::loanPlan).toList(),
                x.payrollLoanApplications().stream().map(BackupResponse::loanApplication).toList(),
                x.commitments().stream().map(BackupResponse::commitment).toList(),
                x.thirdPartyExpenses().stream().map(BackupResponse::thirdPartyExpense).toList(),
                x.thirdPartyReceipts().stream().map(BackupResponse::thirdPartyReceipt).toList());
    }

    private static AccountItem account(Account a) {
        return new AccountItem(a.id(), a.name(), a.type(), a.institution(), a.openingBalance(), a.openingDate(), a.status());
    }

    private static CategoryItem category(Category c, BackupExtras x) {
        return new CategoryItem(c.id(), c.name(), c.kind(), c.isSystem(), c.isArchived(), x.categorySystemKeys().get(c.id()));
    }

    private static CardItem card(CreditCard c) {
        List<BillingRuleItem> rules = c.billingRules().stream().map((BillingRule r) -> new BillingRuleItem(r.effectiveFrom().toString(),
                r.closingDay().day(), r.dueDay().day())).toList();
        return new CardItem(c.id(), c.name(), c.institution(), c.limit(), c.closingDay(), c.dueDay(), c.defaultPaymentAccountId(),
                c.status(), rules, c.closingDayConfirmed(), c.profile().autoDebit(), c.profile().brand(), c.profile().bestPurchaseDay(),
                c.profile().lastDigits());
    }

    private static SharesItem shares(PurchaseShares shares) {
        if (shares == null) {
            return null;
        }
        return new SharesItem(shares.mode(), shares.lines().stream()
                .map(l -> new ShareLineItem(l.personId(), l.bps(), l.amount(), ProvenanceItem.of(l.provenance()))).toList());
    }

    private static TransactionItem transaction(Transaction t, BackupExtras x) {
        List<InstallmentItem> installments = t.installments().stream().map((TransactionInstallment i) -> new InstallmentItem(i.id(),
                i.number(), i.total(), i.amount(), i.effectiveDate(), i.invoiceClosingDate(), i.lateEntry(), i.isCanceled())).toList();
        ForeignCharge charge = x.foreignCharges().get(t.id());
        return new TransactionItem(t.id(), t.type(), t.amount(), t.date(), t.description(), t.notes(), t.origin().source(),
                t.origin().recurrenceId(), t.origin().occurrenceDate(), t.paymentMethod(), t.accountId(), t.counterpartAccountId(),
                t.cardId(), t.categoryId(), t.debtId(), t.refundOfId(), t.investmentDirection(), t.adjustmentDirection(),
                t.adjustmentKind(), t.reason(), t.invoiceClosingDate(), t.lateEntry(), t.status(), t.voidReason(), t.voidedAt(), installments,
                t.incomeCompetence() == null ? null : t.incomeCompetence().toString(), t.nature(), shares(t.purchaseShares()),
                charge == null ? null : charge.original(), charge == null ? null : charge.fxRate().toPlainString(),
                charge == null ? null : charge.iofBps(), t.lastDigits());
    }

    private static DebtItem debt(Debt d) {
        return new DebtItem(d.id(), d.personId(), d.direction(), d.origin(), d.principal(), d.description(), d.date(), d.expectedDate(),
                d.originTransactionId(), d.categoryId(), d.closure(), d.closureDate(), d.closureReason(),
                d.settlements().stream().map(x -> new SettlementItem(x.transactionId(), x.amount(), x.active())).toList(), d.creditorName(),
                d.kind(), d.channel());
    }

    private static PersonItem person(Person p) {
        return new PersonItem(p.id(), p.name(), p.notes(), p.isArchived());
    }

    private static RecurrenceItem recurrence(RecurringTransaction r) {
        ForeignCharge basis = r.foreignBasis();
        SubscriptionTraits traits = r.traits();
        return new RecurrenceItem(r.id(), r.type(), r.amount(), r.description(), r.categoryId(), r.accountId(), r.cardId(),
                r.paymentMethod(), r.schedule().frequency(), r.schedule().interval(), r.schedule().unit(), r.schedule().startDate(),
                r.schedule().endDate(), r.schedule().maxOccurrences(), r.isActive(), r.skippedDates(),
                r.incomeCompetenceOffsetMonths(), r.nature(), shares(r.purchaseShares()), basis == null ? null : basis.original(),
                basis == null ? null : basis.fxRate().toPlainString(), basis == null ? null : basis.iofBps(), traits.subscription(),
                traits.scope(), traits.channel(), traits.overlapGroup(), traits.lastUsedOn(), ProvenanceItem.of(r.provenance()));
    }

    private static GoalItem goal(Goal g) {
        return new GoalItem(g.id(), g.name(), g.target(), g.deadline(), g.priority(), g.description(), g.isArchived(),
                g.reservations().stream().map(r -> new ReservationItem(r.id(), r.accountId(), r.amount(), r.date(), r.note(), r.createdAt()))
                        .toList(), g.kind(), g.metric(), g.direction(), g.baseline(), g.baselineAt(), g.planNote(), g.isFeatured(),
                g.actions().stream().map(BackupResponse::goalAction).toList());
    }

    private static GoalActionItem goalAction(GoalAction a) {
        return new GoalActionItem(a.id(), a.title(), a.frequency(), a.createdAt(), List.copyOf(a.doneIn()));
    }

    private static PostingItem posting(LedgerEntry e) {
        return new PostingItem(e.source().type(), e.source().sourceId(), e.source().installmentId(), e.posting().bookType(),
                e.posting().book().id(), e.posting().amount(), e.posting().effectiveDate(), e.active(), e.posting().competence().toString());
    }

    private static PurchaseGroupItem purchaseGroup(BackupPurchaseGroup g) {
        return new PurchaseGroupItem(g.id(), g.name(), g.interest(), g.declaredTotal(), g.members());
    }

    private static AssetItem asset(Asset a) {
        return new AssetItem(a.id(), a.kind(), a.name(), a.employer(), a.balance(), a.balanceDate(), a.monthlyDeposit(),
                a.availability(), a.countsAsReserve(), a.isArchived(), ProvenanceItem.of(a.provenance()),
                a.history().stream().map(p -> new BalancePointItem(p.balance(), p.date(), p.recordedAt())).toList());
    }

    private static IncomeProfileItem incomeProfile(IncomeProfile p) {
        return new IncomeProfileItem(p.id(), p.source(), p.gross(), p.dependents(),
                p.advance() == null ? null : p.advance().dayOfMonth(), p.advance() == null ? null : p.advance().percentBps(),
                p.balanceDayOfMonth(), p.thirteenth().enabled(), p.thirteenth().firstInstallmentMonth(), p.isArchived(),
                ProvenanceItem.of(p.provenance()), p.deductions().stream().map(BackupResponse::deduction).toList());
    }

    private static DeductionItem deduction(PayrollDeduction d) {
        return new DeductionItem(d.kind(), d.label(), d.amount(), d.percentBps(), d.startsOn(), d.endsOn(), d.debtId(),
                d.interestAmount());
    }

    private static IncomeOccurrenceItem incomeOccurrence(OccurrenceLink l) {
        return new IncomeOccurrenceItem(l.profileId(), l.competence().toString(), l.kind(), l.recurrenceId());
    }

    private static PayrollLoanPlanItem loanPlan(PayrollLoanPlanLink l) {
        return new PayrollLoanPlanItem(l.recurrenceId(), l.plan().debtId(), l.plan().applied(), l.plan().interest());
    }

    private static PayrollLoanApplicationItem loanApplication(PayrollLoanApplication a) {
        return new PayrollLoanApplicationItem(a.transactionId(), a.debtId(), a.applied(), a.interest(), a.principal());
    }

    private static CommitmentItem commitment(TrackedCommitment c) {
        CommitmentClauses clauses = c.clauses();
        return new CommitmentItem(c.id(), c.kind(), c.name(), c.counterparty(), c.notes(), c.declaredTotal(), c.informedPaid(),
                c.installmentsTotal(), c.installmentsPaid(), c.storedNextDueDate(),
                clauses == null ? null : new ClausesItem(clauses.lateFeeBps(), clauses.monthlyInterestBps(),
                        clauses.terminationRetentionBps(), clauses.notes()),
                c.schedule().stream().map(BackupResponse::scheduleItem).toList());
    }

    private static ScheduleItemItem scheduleItem(ScheduleItem i) {
        return new ScheduleItemItem(i.position(), i.label(), i.type(), i.dueDate(), i.amount(), i.paid(), i.paidOn(),
                i.shares().stream().map((ItemShare s) -> new ItemShareItem(s.personId(), s.amount())).toList(), i.coBuyerPersonId(),
                ProvenanceItem.of(i.provenance()));
    }

    private static ThirdPartyExpenseItem thirdPartyExpense(ThirdPartyExpense e) {
        return new ThirdPartyExpenseItem(e.id(), e.transactionId(), e.personId(), e.note(), e.amount());
    }

    private static ThirdPartyReceiptItem thirdPartyReceipt(ThirdPartyReceipt r) {
        return new ThirdPartyReceiptItem(r.id(), r.personId(), r.transactionId(), r.amount(), r.receivedOn(), r.note(), r.accountId(),
                r.settlement(), r.ledgered());
    }
}
