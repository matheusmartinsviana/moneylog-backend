package com.moneylog.core.insights.app;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.moneylog.core.account.domain.LiquidityClass;
import com.moneylog.core.card.domain.AvailableLimit;
import com.moneylog.core.card.domain.AvailableLimitCalculator;
import com.moneylog.core.card.domain.CardStatus;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.domain.CashPosition;
import com.moneylog.core.finance.domain.Commitment;
import com.moneylog.core.finance.domain.CommitmentKind;
import com.moneylog.core.finance.domain.CommitmentSchedule;
import com.moneylog.core.finance.domain.ConsumptionView;
import com.moneylog.core.finance.domain.DebtOverviewCalculator;
import com.moneylog.core.finance.domain.EntryLens;
import com.moneylog.core.finance.domain.EntryReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.Ledger;
import com.moneylog.core.finance.domain.MonthlySummary;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalPlan;
import com.moneylog.core.goal.domain.GoalReservation;
import com.moneylog.core.goal.domain.GoalStatus;
import com.moneylog.core.insights.domain.DayFacts;
import com.moneylog.core.insights.domain.MonthFacts;
import com.moneylog.core.insights.domain.MonthSnapshot;
import com.moneylog.core.insights.domain.SubscriptionDetector;
import com.moneylog.core.insights.domain.Thresholds;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.FlowDirection;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionSource;

public final class InsightContext {

    enum Kind {
        INSTALLMENT,
        RECURRING,
        DEBT,
        VARIABLE
    }

    record Line(EntryReport.Line line, Kind kind) {
    }

    record Classified(Competence month, List<Line> lines, Money income, List<EntryReport.Line> incomeLines, Money consumption,
                      Money installments, Money recurring, Money debt, Money variable, Map<UUID, Money> byCategory) {

        Money essential() {
            return installments.add(recurring).add(debt);
        }
    }

    private static final Money ZERO = Money.zero(CurrencyCode.BRL);

    private final UUID userId;
    private final LocalDate today;
    private final FinancialRecords records;
    private final Ledger ledger;
    private final Collection<RecurringTransaction> recurrences;
    private final Map<UUID, String> categoryNames;
    private final Supplier<List<GoalDetails>> goalDetails;
    private final Map<UUID, Transaction> transactions = new HashMap<>();
    private final Map<Competence, Classified> months = new HashMap<>();
    private final Map<LocalDate, CommitmentSchedule> schedules = new HashMap<>();
    private final Map<Competence, Money> cardOutstanding = new HashMap<>();
    private Map<Competence, Money> installmentByCompetence;
    private Map<UUID, List<Posting>> cardPostings;
    private List<GoalDetails> goals;

    public InsightContext(UUID userId, LocalDate today, FinancialRecords records, Collection<RecurringTransaction> recurrences,
                          Map<UUID, String> categoryNames, Supplier<List<GoalDetails>> goalDetails) {
        this(userId, today, records, ConsumptionView.MINE, recurrences, categoryNames, goalDetails);
    }

    public InsightContext(UUID userId, LocalDate today, FinancialRecords records, ConsumptionView view,
                          Collection<RecurringTransaction> recurrences, Map<UUID, String> categoryNames,
                          Supplier<List<GoalDetails>> goalDetails) {
        this.userId = userId;
        this.today = today;
        this.records = records;
        this.ledger = ConsumptionView.orDefault(view).ledgerOf(records);
        this.recurrences = List.copyOf(recurrences);
        this.categoryNames = Map.copyOf(categoryNames);
        this.goalDetails = goalDetails;
        records.transactions().forEach(t -> transactions.put(t.id(), t));
    }

    public UUID userId() {
        return userId;
    }

    public LocalDate today() {
        return today;
    }

    public MonthFacts monthFacts(Competence month) {
        Classified current = classified(month);
        boolean isCurrent = month.equals(Competence.from(today));
        List<MonthSnapshot> history = new ArrayList<>();
        for (int back = Thresholds.HISTORY_MONTHS; back >= 1; back--) {
            MonthSnapshot snapshot = snapshot(month.minusMonths(back));
            if (!snapshot.isEmpty()) {
                history.add(snapshot);
            }
        }
        MonthFacts.Budget budget = isCurrent ? budget(today) : null;
        List<Money> dailyVariable = isCurrent ? variableByDay(current, 1, today.getDayOfMonth()) : List.of();
        Money pendingRecurring = budget == null ? ZERO : budget.pendingRecurring();
        Money recurringOut = current.recurring().add(pendingRecurring);
        boolean hasCash = hasCashAccounts();
        CashPosition cash = CashPosition.at(records, ledger, today);
        return new MonthFacts(today, month, snapshot(current), history, budget, dailyVariable, recurringOut, current.installments(),
                debtPaymentsDue(month), DebtOverviewCalculator.total(records, today), cash.inAccounts(), hasCash, installmentLoad(month),
                isCurrent ? goalFacts() : List.of(), isCurrent ? cardFacts() : List.of(), cardOutstanding(month), categoryNames,
                subscriptionLines(month, isCurrent), registeredDescriptions());
    }

    public DayFacts dayFacts(LocalDate date) {
        Competence month = Competence.from(date);
        Classified current = classified(month);
        CommitmentSchedule schedule = schedule(date);
        CashPosition cash = CashPosition.at(records, ledger, date);
        return new DayFacts(date, budget(date), variableByDay(current, 1, date.getDayOfMonth() - 1), variableOn(current, date),
                cash.inAccounts(), hasCashAccounts(), dueWeek(schedule, date), events(schedule, date), todayEntries(current, date),
                categoryHistory(month), categoryNames, closings(schedule, date));
    }

    Classified classified(Competence month) {
        return months.computeIfAbsent(month, this::buildClassified);
    }

    private Classified buildClassified(Competence month) {
        EntryReport consumption = EntryReport.of(records, ledger, EntryLens.CONSUMPTION, month);
        EntryReport income = EntryReport.of(records, ledger, EntryLens.INCOME, month);
        List<Line> lines = new ArrayList<>(consumption.lines().size());
        Money installments = ZERO;
        Money recurring = ZERO;
        Money debt = ZERO;
        Money variable = ZERO;
        for (EntryReport.Line line : consumption.lines()) {
            Kind kind = kindOf(line);
            lines.add(new Line(line, kind));
            switch (kind) {
                case INSTALLMENT -> installments = installments.add(line.amount());
                case RECURRING -> recurring = recurring.add(line.amount());
                case DEBT -> debt = debt.add(line.amount());
                case VARIABLE -> variable = variable.add(line.amount());
            }
        }
        return new Classified(month, List.copyOf(lines), income.total(), income.lines(), consumption.total(), installments, recurring, debt,
                variable, consumption.byCategory());
    }

    private Kind kindOf(EntryReport.Line line) {
        if (line.debtId() != null) {
            return Kind.DEBT;
        }
        if (line.installmentId() != null) {
            return Kind.INSTALLMENT;
        }
        Transaction tx = line.transactionId() == null ? null : transactions.get(line.transactionId());
        if (tx != null && tx.origin().source() == TransactionSource.RECURRENCE) {
            return Kind.RECURRING;
        }
        return Kind.VARIABLE;
    }

    private MonthSnapshot snapshot(Competence month) {
        return snapshot(classified(month));
    }

    private MonthSnapshot snapshot(Classified c) {
        return new MonthSnapshot(c.month(), c.income(), c.consumption(), c.essential(), c.byCategory());
    }

    private List<Money> variableByDay(Classified c, int fromDay, int toDay) {
        if (toDay < fromDay) {
            return List.of();
        }
        long[] cents = new long[toDay - fromDay + 1];
        for (Line l : c.lines()) {
            int day = l.line().date().getDayOfMonth();
            if (l.kind() == Kind.VARIABLE && Competence.from(l.line().date()).equals(c.month()) && day >= fromDay && day <= toDay) {
                cents[day - fromDay] = Math.addExact(cents[day - fromDay], l.line().amount().cents());
            }
        }
        List<Money> out = new ArrayList<>(cents.length);
        for (long c1 : cents) {
            out.add(Money.ofCents(c1, CurrencyCode.BRL));
        }
        return out;
    }

    private static Money variableOn(Classified c, LocalDate date) {
        Money total = ZERO;
        for (Line l : c.lines()) {
            if (l.kind() == Kind.VARIABLE && l.line().date().equals(date)) {
                total = total.add(l.line().amount());
            }
        }
        return total;
    }

    MonthFacts.Budget budget(LocalDate asOf) {
        Competence month = Competence.from(asOf);
        Classified c = classified(month);
        Money received = ZERO;
        for (EntryReport.Line l : c.incomeLines()) {
            if (!l.date().isAfter(asOf) && l.competence().equals(month)) {
                received = received.add(l.amount());
            }
        }
        Money spent = ZERO;
        Money later = ZERO;
        for (Line l : c.lines()) {
            if (l.line().date().isAfter(asOf)) {
                later = later.add(l.line().amount());
            } else {
                spent = spent.add(l.line().amount());
            }
        }
        return new MonthFacts.Budget(received, spent, later, pendingRecurring(asOf, month), reservedInMonth(asOf, month));
    }

    private Money pendingRecurring(LocalDate asOf, Competence month) {
        Money total = ZERO;
        for (Commitment c : schedule(asOf).items()) {
            if (c.kind() == CommitmentKind.RECURRENCE && c.direction() == FlowDirection.OUT && !c.dueDate().isBefore(asOf)
                    && !c.dueDate().isAfter(month.lastDay())) {
                total = total.add(c.amount());
            }
        }
        return total;
    }

    private Money reservedInMonth(LocalDate asOf, Competence month) {
        Money net = ZERO;
        for (Goal goal : records.goals()) {
            if (goal.isArchived()) {
                continue;
            }
            for (GoalReservation r : goal.reservations()) {
                if (month.contains(r.date()) && !r.date().isAfter(asOf)) {
                    net = net.add(r.amount());
                }
            }
        }
        return net.isNegative() ? ZERO : net;
    }

    CommitmentSchedule schedule(LocalDate asOf) {
        return schedules.computeIfAbsent(asOf, d -> CommitmentSchedule.of(records, recurrences, d));
    }

    private boolean hasCashAccounts() {
        return records.accounts().stream().anyMatch(a -> a.isActive() && a.liquidity() == LiquidityClass.CASH);
    }

    private Money debtPaymentsDue(Competence month) {
        Money total = ZERO;
        for (Commitment c : schedule(today).items()) {
            if (c.kind() == CommitmentKind.DEBT_PAYABLE && month.contains(c.dueDate())) {
                total = total.add(c.amount());
            }
        }
        return total;
    }

    private Money cardOutstanding(Competence month) {
        return cardOutstanding.computeIfAbsent(month, m -> MonthlySummary.of(records, ledger, m, today).card().outstanding());
    }

    private List<Money> installmentLoad(Competence month) {
        if (installmentByCompetence == null) {
            Map<Competence, Money> byCompetence = new HashMap<>();
            for (LedgerEntry e : ledger.entries()) {
                if (e.posting().bookType() == BookType.EXPENSE && e.source().installmentId() != null) {
                    byCompetence.merge(e.posting().competence(), e.posting().amount(), Money::add);
                }
            }
            installmentByCompetence = byCompetence;
        }
        List<Money> load = new ArrayList<>(Thresholds.INSTALLMENT_HORIZON_MONTHS);
        for (int k = 0; k < Thresholds.INSTALLMENT_HORIZON_MONTHS; k++) {
            load.add(installmentByCompetence.getOrDefault(month.plusMonths(k), ZERO));
        }
        return load;
    }

    private List<MonthFacts.GoalFact> goalFacts() {
        if (goals == null) {
            goals = goalDetails.get();
        }
        List<MonthFacts.GoalFact> facts = new ArrayList<>();
        for (GoalDetails d : goals) {
            if (d.status() != GoalStatus.ACTIVE || d.goal().isArchived()) {
                continue;
            }
            GoalPlan plan = d.planning().plan();
            Integer streak = d.planning().consistency() == null ? 0 : d.planning().consistency().streak();
            facts.add(new MonthFacts.GoalFact(d.goal().id(), d.goal().name(), plan != null, plan != null && plan.onTrack(),
                    plan == null || plan.requiredPace() == null ? ZERO : plan.requiredPace(),
                    plan == null || plan.gap() == null ? ZERO : plan.gap(), plan == null ? 0 : plan.monthsLeft(), streak));
        }
        return facts;
    }

    private List<MonthFacts.CardFact> cardFacts() {
        if (cardPostings == null) {
            Map<UUID, List<Posting>> byCard = new HashMap<>();
            for (LedgerEntry e : ledger.entries()) {
                if (e.posting().bookType() == BookType.CARD) {
                    byCard.computeIfAbsent(e.posting().book().id(), k -> new ArrayList<>()).add(e.posting());
                }
            }
            cardPostings = byCard;
        }
        List<MonthFacts.CardFact> facts = new ArrayList<>();
        for (CreditCard card : records.cards()) {
            if (card.status() != CardStatus.ACTIVE) {
                continue;
            }
            if (card.limit() == null) {
                facts.add(new MonthFacts.CardFact(card.id(), card.name(), null, ZERO));
                continue;
            }
            AvailableLimit available = AvailableLimitCalculator.calculate(card, cardPostings.getOrDefault(card.id(), List.of()));
            Money used = card.limit().subtract(available.available());
            facts.add(new MonthFacts.CardFact(card.id(), card.name(), card.limit(), used.isNegative() ? ZERO : used));
        }
        return facts;
    }

    private List<SubscriptionDetector.Line> subscriptionLines(Competence month, boolean isCurrent) {
        LocalDate end = isCurrent ? today : month.lastDay();
        List<SubscriptionDetector.Line> lines = new ArrayList<>();
        for (int back = Thresholds.HISTORY_MONTHS - 1; back >= 0; back--) {
            for (Line l : classified(month.minusMonths(back)).lines()) {
                EntryReport.Line line = l.line();
                if (l.kind() == Kind.VARIABLE && line.description() != null && !line.description().isBlank() && line.amount().isPositive()
                        && !line.date().isAfter(end)) {
                    lines.add(new SubscriptionDetector.Line(line.date(), line.description(), line.amount()));
                }
            }
        }
        return lines;
    }

    private Set<String> registeredDescriptions() {
        Set<String> descriptions = new HashSet<>();
        for (RecurringTransaction r : recurrences) {
            if (r.direction() == FlowDirection.OUT) {
                descriptions.add(SubscriptionDetector.normalize(r.description()));
            }
        }
        return descriptions;
    }

    private List<DayFacts.Due> dueWeek(CommitmentSchedule schedule, LocalDate date) {
        LocalDate until = date.plusDays(Thresholds.DUE_SOON_DAYS);
        List<DayFacts.Due> due = new ArrayList<>();
        for (Commitment c : schedule.items()) {
            if (c.direction() != FlowDirection.OUT || c.dueDate().isAfter(until)) {
                continue;
            }
            if (c.dueDate().isBefore(date) && !(c.kind() == CommitmentKind.INVOICE || c.kind() == CommitmentKind.DEBT_PAYABLE)) {
                continue;
            }
            due.add(new DayFacts.Due(c.dueDate(), c.amount(), isInvoice(c) ? invoiceRef(c) : null));
        }
        return due;
    }

    private List<DayFacts.CashEvent> events(CommitmentSchedule schedule, LocalDate date) {
        LocalDate until = date.plusDays(Thresholds.FORECAST_DAYS);
        List<DayFacts.CashEvent> events = new ArrayList<>();
        for (Commitment c : schedule.items()) {
            if (c.dueDate().isAfter(until)) {
                continue;
            }
            boolean stale = c.dueDate().isBefore(date);
            if (c.direction() == FlowDirection.OUT) {
                if (stale && c.kind() == CommitmentKind.RECURRENCE) {
                    continue;
                }
                events.add(new DayFacts.CashEvent(stale ? date : c.dueDate(), c.amount().negate()));
            } else if (!stale && (c.kind() == CommitmentKind.RECURRENCE || c.kind() == CommitmentKind.SCHEDULED_TRANSACTION)) {
                events.add(new DayFacts.CashEvent(c.dueDate(), c.amount()));
            }
        }
        return events;
    }

    private List<DayFacts.TodayEntry> todayEntries(Classified c, LocalDate date) {
        List<DayFacts.TodayEntry> entries = new ArrayList<>();
        int index = 0;
        for (Line l : c.lines()) {
            EntryReport.Line line = l.line();
            index++;
            if (line.date().equals(date) && line.categoryId() != null && line.amount().isPositive()
                    && (l.kind() == Kind.VARIABLE || l.kind() == Kind.RECURRING)) {
                String subject = line.transactionId() != null ? line.transactionId().toString() : date + "#" + index;
                entries.add(new DayFacts.TodayEntry(subject, line.categoryId(), line.amount()));
            }
        }
        return entries;
    }

    private Map<UUID, List<Money>> categoryHistory(Competence month) {
        Map<UUID, List<Money>> byCategory = new HashMap<>();
        for (int back = Thresholds.HISTORY_MONTHS; back >= 1; back--) {
            for (Line l : classified(month.minusMonths(back)).lines()) {
                EntryReport.Line line = l.line();
                if ((l.kind() == Kind.VARIABLE || l.kind() == Kind.RECURRING) && line.categoryId() != null && line.amount().isPositive()) {
                    byCategory.computeIfAbsent(line.categoryId(), k -> new ArrayList<>()).add(line.amount());
                }
            }
        }
        return byCategory;
    }

    private List<DayFacts.Closing> closings(CommitmentSchedule schedule, LocalDate date) {
        List<DayFacts.Closing> closings = new ArrayList<>();
        for (Commitment c : schedule.items()) {
            if (c.kind() == CommitmentKind.INVOICE_FORECAST && c.refDate() != null && !c.refDate().isBefore(date)) {
                closings.add(new DayFacts.Closing(invoiceRef(c), c.label(), c.refDate(), c.amount()));
            }
        }
        return closings;
    }

    private static boolean isInvoice(Commitment c) {
        return c.kind() == CommitmentKind.INVOICE || c.kind() == CommitmentKind.INVOICE_FORECAST;
    }

    private static String invoiceRef(Commitment c) {
        return "invoice." + c.refId() + "." + c.refDate();
    }
}
