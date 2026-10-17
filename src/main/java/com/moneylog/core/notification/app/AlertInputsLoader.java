package com.moneylog.core.notification.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.card.app.gateways.InvoiceDocumentGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.core.card.domain.CreditCardInvoice;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.commitment.app.gateways.CommitmentGateway;
import com.moneylog.core.commitment.domain.CommitmentKind;
import com.moneylog.core.commitment.domain.FinancingPending;
import com.moneylog.core.commitment.domain.ScheduleItem;
import com.moneylog.core.commitment.domain.ScheduleItemType;
import com.moneylog.core.commitment.domain.TrackedCommitment;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.EffectiveRate;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.statement.GetOwnerStatementUseCase;
import com.moneylog.core.finance.app.statement.GetOwnerStatementUseCase.GetOwnerStatementQuery;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.OwnerStatement;
import com.moneylog.core.finance.domain.ThirdPartyReceivables;
import com.moneylog.core.notification.app.gateways.AlertSourcesGateway;
import com.moneylog.core.notification.app.gateways.AlertSourcesGateway.Created;
import com.moneylog.core.notification.domain.AlertInputs;
import com.moneylog.core.notification.domain.AlertInputs.CardInfo;
import com.moneylog.core.notification.domain.AlertInputs.DocumentComparison;
import com.moneylog.core.notification.domain.AlertInputs.CashOutCost;
import com.moneylog.core.notification.domain.AlertInputs.MonthSurplus;
import com.moneylog.core.notification.domain.AlertInputs.NewDebt;
import com.moneylog.core.notification.domain.AlertInputs.OverdueReimbursement;
import com.moneylog.core.notification.domain.AlertInputs.RealEstateDue;
import com.moneylog.core.notification.domain.AlertInputs.SubscriptionInfo;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.IntervalUnit;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;

public class AlertInputsLoader {

    private final GetOwnerStatementUseCase statement;
    private final CommitmentGateway commitments;
    private final DebtGateway debts;
    private final ThirdPartyExpenseGateway marks;
    private final PersonGateway persons;
    private final AlertSourcesGateway sources;
    private final InvoiceDocumentGateway invoiceDocuments;

    @SuppressWarnings("java:S107")
    public AlertInputsLoader(GetOwnerStatementUseCase statement, CommitmentGateway commitments, DebtGateway debts, ThirdPartyExpenseGateway marks,
                             PersonGateway persons, AlertSourcesGateway sources, InvoiceDocumentGateway invoiceDocuments) {
        this.invoiceDocuments = invoiceDocuments;
        this.statement = statement;
        this.commitments = commitments;
        this.debts = debts;
        this.marks = marks;
        this.persons = persons;
        this.sources = sources;
    }

    public AlertInputs load(UUID userId, LocalDate today, ZoneId zone, FinancialRecords records, Collection<RecurringTransaction> recurrences) {
        Instant from = today.atStartOfDay(zone).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(zone).toInstant();
        Created created = sources.createdBetween(userId, from, to);
        List<TrackedCommitment> tracked = commitments.findAll(userId);
        OwnerStatement owner = statement.handle(new GetOwnerStatementQuery(userId, null, null, today));
        return new AlertInputs(cards(records), negativeMonths(owner), owner.openingBalance(), realEstateDues(tracked),
                FinancingPending.exists(tracked), newDebts(created), cashOutCosts(userId, records, created), subscriptions(recurrences, created, today),
                overdueReimbursements(userId, records, today), invoiceDocuments(userId, records));
    }

    private List<DocumentComparison> invoiceDocuments(UUID userId, FinancialRecords records) {
        Map<UUID, Money> documented = invoiceDocuments.amountsOf(userId);
        if (documented.isEmpty()) {
            return List.of();
        }
        List<DocumentComparison> result = new ArrayList<>();
        for (CreditCard card : records.cards()) {
            for (CreditCardInvoice invoice : CardInvoices.chainFor(card, records.transactions()).invoices()) {
                Money amount = documented.get(InvoiceIds.of(card.id(), invoice.closingDate()));
                if (amount != null) {
                    result.add(new DocumentComparison("INVOICE", InvoiceIds.of(card.id(), invoice.closingDate()),
                            "Fatura " + card.name() + " " + invoice.referenceMonth(), invoice.total(), amount));
                }
            }
        }
        return result;
    }

    private static Map<UUID, CardInfo> cards(FinancialRecords records) {
        Map<UUID, CardInfo> result = new HashMap<>();
        for (CreditCard card : records.cards()) {
            result.put(card.id(), new CardInfo(card.name(), card.autoDebit()));
        }
        return result;
    }

    private static List<MonthSurplus> negativeMonths(OwnerStatement owner) {
        return owner.months().stream().filter(OwnerStatement.MonthColumn::negative)
                .map(m -> new MonthSurplus(m.competence(), m.surplus())).toList();
    }

    private static List<RealEstateDue> realEstateDues(List<TrackedCommitment> tracked) {
        List<RealEstateDue> result = new ArrayList<>();
        for (TrackedCommitment commitment : tracked) {
            if (commitment.kind() != CommitmentKind.LONG_TERM_PROJECT) {
                continue;
            }
            for (ScheduleItem item : commitment.schedule()) {
                if (!item.isPaid() && item.dueDate() != null && item.type() != ScheduleItemType.FINANCING) {
                    result.add(new RealEstateDue(commitment.id(), item.dueDate(), item.ownerShare()));
                }
            }
        }
        return result;
    }

    private static List<NewDebt> newDebts(Created created) {
        return created.purchases().stream().map(p -> new NewDebt(p.transactionId(), p.amount(), p.installments())).toList();
    }

    private List<CashOutCost> cashOutCosts(UUID userId, FinancialRecords records, Created created) {
        if (created.cashOuts().isEmpty()) {
            return List.of();
        }
        List<UUID> transactionIds = created.cashOuts().stream().map(AlertSourcesGateway.NewCashOut::transactionId).toList();
        Map<UUID, List<Money>> schedules = debts.installmentSchedules(userId, transactionIds);
        Map<UUID, Debt> byId = new HashMap<>();
        records.debts().forEach(d -> byId.put(d.id(), d));
        List<CashOutCost> result = new ArrayList<>();
        for (AlertSourcesGateway.NewCashOut cashOut : created.cashOuts()) {
            Debt debt = byId.get(cashOut.debtId());
            List<Money> schedule = schedules.getOrDefault(cashOut.transactionId(), List.of());
            if (debt == null || schedule.isEmpty()) {
                continue;
            }
            EffectiveRate.irr(debt.principal(), schedule)
                    .ifPresent(rate -> result.add(new CashOutCost(debt.id(), rate.monthlyBps(), schedule.get(0), schedule.size())));
        }
        return result;
    }

    private static List<SubscriptionInfo> subscriptions(Collection<RecurringTransaction> recurrences, Created created, LocalDate today) {
        List<SubscriptionInfo> result = new ArrayList<>();
        for (RecurringTransaction r : recurrences) {
            if (!r.isSubscription() || !r.isActive() || r.type() == TransactionType.INCOME || r.isEnded(today)) {
                continue;
            }
            boolean weekly = r.schedule().frequency() == Frequency.WEEKLY || (r.schedule().frequency() == Frequency.CUSTOM
                    && r.schedule().unit() == IntervalUnit.WEEKS && r.schedule().interval() == 1);
            result.add(new SubscriptionInfo(r.id(), r.description(), r.traits().overlapGroup(), r.traits().lastUsedOn(), weekly,
                    created.recurrenceIds().contains(r.id())));
        }
        return result;
    }

    private List<OverdueReimbursement> overdueReimbursements(UUID userId, FinancialRecords records, LocalDate today) {
        Map<UUID, Transaction> purchases = new HashMap<>();
        for (Transaction t : records.transactions()) {
            if (t.isActive() && t.type() == TransactionType.CARD_PURCHASE && t.cardId() != null) {
                purchases.put(t.id(), t);
            }
        }
        if (purchases.isEmpty()) {
            return List.of();
        }
        List<ThirdPartyReceipt> receipts = marks.findAllReceipts(userId);
        Set<UUID> personIds = new HashSet<>();
        records.transactions().forEach(t -> {
            if (t.purchaseShares() != null) {
                personIds.addAll(t.purchaseShares().personIds());
            }
        });
        marks.findAll(userId).forEach(e -> personIds.add(e.personId()));
        Map<UUID, String> names = new HashMap<>();
        persons.findByIds(userId, personIds).forEach((id, person) -> names.put(id, person.name()));
        ThirdPartyReceivables.Overview overview = ThirdPartyReceivables.of(records, marks.findAll(userId), receipts, names, today,
                Competence.from(today));
        Map<UUID, Map<String, Money>> pendingByTransaction = new LinkedHashMap<>();
        for (ThirdPartyReceivables.PersonLine line : overview.people()) {
            for (ThirdPartyReceivables.Item item : line.items()) {
                boolean card = item.source() == ThirdPartyReceivables.Source.EXPENSE || item.source() == ThirdPartyReceivables.Source.CARD_SHARE;
                if (card && item.pending().isPositive() && purchases.containsKey(item.refId())) {
                    pendingByTransaction.computeIfAbsent(item.refId(), k -> new LinkedHashMap<>()).merge(line.name(), item.pending(), Money::add);
                }
            }
        }
        Map<UUID, InvoiceChain> chains = new HashMap<>();
        List<OverdueReimbursement> result = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, Money>> entry : pendingByTransaction.entrySet()) {
            Transaction tx = purchases.get(entry.getKey());
            LocalDate due = firstInvoiceDue(records, chains, tx);
            if (due == null || !today.isAfter(due)) {
                continue;
            }
            Money total = Money.sum(entry.getValue().values(), CurrencyCode.BRL);
            result.add(new OverdueReimbursement(tx.id(), String.join(", ", entry.getValue().keySet()), total, due));
        }
        result.sort(Comparator.comparing(OverdueReimbursement::invoiceDueDate).thenComparing(OverdueReimbursement::transactionId));
        return result;
    }

    private static LocalDate firstInvoiceDue(FinancialRecords records, Map<UUID, InvoiceChain> chains, Transaction tx) {
        CreditCard card = records.cards().stream().filter(c -> c.id().equals(tx.cardId())).findFirst().orElse(null);
        if (card == null) {
            return null;
        }
        InvoiceChain chain = chains.computeIfAbsent(card.id(), id -> CardInvoices.chainFor(card, records.transactions()));
        return tx.installments().stream().filter(i -> !i.isCanceled()).min(Comparator.comparingInt(TransactionInstallment::number))
                .map(i -> chain.invoice(i.invoiceClosingDate())).map(CreditCardInvoice::dueDate).orElse(null);
    }
}
