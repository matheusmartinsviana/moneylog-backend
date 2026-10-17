package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.thirdparty.domain.ThirdPartyExpense;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;

public final class ThirdPartyReceivables {

    private ThirdPartyReceivables() {
    }

    public enum Source {
        DEBT, EXPENSE, RECEIPT, CARD_SHARE
    }

    public record Item(Source source, UUID refId, String description, LocalDate date, LocalDate expectedDate, Money owed, Money received,
                       Money pending, String status, List<CardShareReceivables.Part> installments) {

        public Item(Source source, UUID refId, String description, LocalDate date, LocalDate expectedDate, Money owed, Money received,
                    Money pending, String status) {
            this(source, refId, description, date, expectedDate, owed, received, pending, status, List.of());
        }
    }

    public record PersonMonth(Competence competence, Money owed, Money received, Money pending) {
    }

    public record PersonLine(UUID personId, String name, Money owed, Money received, Money pending, List<Item> items,
                             List<PersonMonth> months) {

        public PersonLine(UUID personId, String name, Money owed, Money received, Money pending, List<Item> items) {
            this(personId, name, owed, received, pending, items, List.of());
        }
    }

    public record MonthView(Competence competence, Money grossConsumption, Money refunds, Money personalNet,
                            Money thirdPartyConsumption, Money thirdPartyReimbursed, Money thirdPartyPending) {
    }

    public record Overview(LocalDate asOf, Money owed, Money received, Money pending, List<PersonLine> people, MonthView month) {
    }

    public static Overview of(FinancialRecords records, List<ThirdPartyExpense> marks, Map<UUID, String> personNames, LocalDate today,
                              Competence competence) {
        return of(records, marks, List.of(), personNames, today, competence);
    }

    public static Overview of(FinancialRecords records, List<ThirdPartyExpense> marks, List<com.moneylog.core.thirdparty.domain.ThirdPartyReceipt> receipts,
                              Map<UUID, String> personNames, LocalDate today, Competence competence) {
        return of(records, marks, receipts, Map.of(), personNames, today, competence);
    }

    public static Overview of(FinancialRecords records, List<ThirdPartyExpense> marks, List<com.moneylog.core.thirdparty.domain.ThirdPartyReceipt> receipts,
                              Map<CardShareReceivables.ShareKey, Competence> expectedMonths, Map<UUID, String> personNames, LocalDate today,
                              Competence competence) {
        Money zero = Money.zero(CurrencyCode.BRL);
        Map<UUID, Transaction> txs = records.transactionsById();
        Map<UUID, List<Item>> byPerson = new LinkedHashMap<>();
        for (Debt d : records.debts()) {
            if (d.direction() != DebtDirection.RECEIVABLE || d.personId() == null
                    || d.status() == DebtStatus.CANCELED || d.status() == DebtStatus.VOIDED) {
                continue;
            }
            byPerson.computeIfAbsent(d.personId(), k -> new ArrayList<>()).add(new Item(Source.DEBT, d.id(), d.description(), d.date(),
                    d.expectedDate(), d.principal(), d.settled(), d.outstanding(), d.status().name()));
        }
        Set<UUID> markedIds = marks.stream().map(ThirdPartyExpense::transactionId).collect(Collectors.toSet());
        for (ThirdPartyExpense mark : marks) {
            Transaction tx = txs.get(mark.transactionId());
            if (tx == null || !tx.isActive()) {
                continue;
            }
            Money owed = mark.amount() != null ? mark.amount() : tx.chargedAmount();
            Money received = refundsOf(records, tx.id()).add(Money.sum(receipts.stream()
                    .filter(r -> tx.id().equals(r.transactionId())).map(com.moneylog.core.thirdparty.domain.ThirdPartyReceipt::amount).toList(), CurrencyCode.BRL));
            Money pending = received.isLessThan(owed) ? owed.subtract(received) : zero;
            byPerson.computeIfAbsent(mark.personId(), k -> new ArrayList<>()).add(new Item(Source.EXPENSE, tx.id(), tx.description(),
                    tx.date(), null, owed, received, pending, pending.isZero() ? "RECEIVED" : received.isZero() ? "PENDING" : "PARTIAL"));
        }
        List<CardShareReceivables.PurchaseLine> shareLines = CardShareReceivables.lines(records, receipts, expectedMonths);
        Map<UUID, Map<Competence, Money[]>> monthsByPerson = new LinkedHashMap<>();
        for (CardShareReceivables.PurchaseLine line : shareLines) {
            Transaction tx = txs.get(line.transactionId());
            byPerson.computeIfAbsent(line.personId(), k -> new ArrayList<>()).add(new Item(Source.CARD_SHARE, tx.id(), tx.description(),
                    tx.date(), null, line.owed(), line.received(), line.pending(),
                    line.pending().isZero() ? "RECEIVED" : line.received().isZero() ? "PENDING" : "PARTIAL",
                    line.parts().stream().filter(CardShareReceivables.Part::editable).toList()));
            Map<Competence, Money[]> months = monthsByPerson.computeIfAbsent(line.personId(), k -> new java.util.TreeMap<>());
            line.months().forEach((competenceKey, amounts) -> {
                Money[] acc = months.computeIfAbsent(competenceKey, k -> new Money[] {zero, zero});
                acc[0] = acc[0].add(amounts.owed());
                acc[1] = acc[1].add(amounts.received());
            });
        }
        for (com.moneylog.core.thirdparty.domain.ThirdPartyReceipt r : receipts) {
            if (r.transactionId() == null) {
                byPerson.computeIfAbsent(r.personId(), k -> new ArrayList<>()).add(new Item(Source.RECEIPT, r.id(), r.note(),
                        r.receivedOn() == null ? today : r.receivedOn(), null, r.amount(), r.amount(), zero, "RECEIVED"));
            }
        }
        List<PersonLine> lines = new ArrayList<>();
        Money owedT = zero;
        Money receivedT = zero;
        Money pendingT = zero;
        for (Map.Entry<UUID, List<Item>> e : byPerson.entrySet()) {
            Money o = zero;
            Money r = zero;
            Money p = zero;
            List<Item> items = e.getValue().stream().sorted(Comparator.comparing(Item::date).thenComparing(Item::refId)).toList();
            for (Item i : items) {
                o = o.add(i.owed());
                r = r.add(i.received());
                p = p.add(i.pending());
            }
            List<PersonMonth> months = monthsByPerson.getOrDefault(e.getKey(), Map.of()).entrySet().stream()
                    .map(m -> new PersonMonth(m.getKey(), m.getValue()[0], m.getValue()[1],
                            m.getValue()[0].subtract(m.getValue()[1]))).toList();
            lines.add(new PersonLine(e.getKey(), personNames.getOrDefault(e.getKey(), ""), o, r, p, items, months));
            owedT = owedT.add(o);
            receivedT = receivedT.add(r);
            pendingT = pendingT.add(p);
        }
        lines.sort(Comparator.comparing(PersonLine::name));
        return new Overview(today, owedT, receivedT, pendingT, List.copyOf(lines), month(records, markedIds, competence, shareLines));
    }

    private static Money refundsOf(FinancialRecords records, UUID originalId) {
        return Money.sum(records.transactions().stream()
                .filter(t -> t.isActive() && t.type() == TransactionType.REFUND && originalId.equals(t.refundOfId()))
                .map(Transaction::amount).toList(), CurrencyCode.BRL);
    }

    private static MonthView month(FinancialRecords records, Set<UUID> markedIds, Competence competence,
                                   List<CardShareReceivables.PurchaseLine> shareLines) {
        Money zero = Money.zero(CurrencyCode.BRL);
        ConsumptionReport report = ConsumptionReport.of(records, records.ledger(), competence);
        Money shareOwed = zero;
        Money shareReceived = zero;
        for (CardShareReceivables.PurchaseLine line : shareLines) {
            CardShareReceivables.MonthAmounts amounts = line.months().get(competence);
            if (amounts != null) {
                shareOwed = shareOwed.add(amounts.owed());
                shareReceived = shareReceived.add(amounts.received());
            }
        }
        Money gross = report.expensesPaid().add(report.credit()).add(report.debtConsumption()).add(shareOwed);
        Money thirdConsumption = zero;
        for (LedgerEntry e : records.ledger().inCompetence(BookType.EXPENSE, competence)) {
            if (e.source().transactionId() != null && markedIds.contains(e.source().transactionId())) {
                thirdConsumption = thirdConsumption.add(e.posting().amount());
            }
        }
        Money reimbursed = zero;
        for (Transaction t : records.transactions()) {
            if (t.isActive() && t.type() == TransactionType.REFUND && t.refundOfId() != null && markedIds.contains(t.refundOfId())
                    && competence.contains(t.date())) {
                reimbursed = reimbursed.add(t.amount());
            }
        }
        Money shareMonthPending = shareOwed.subtract(shareReceived);
        Money pending = (reimbursed.isLessThan(thirdConsumption) ? thirdConsumption.subtract(reimbursed) : zero).add(shareMonthPending);
        thirdConsumption = thirdConsumption.add(shareOwed);
        reimbursed = reimbursed.add(shareReceived);
        return new MonthView(competence, gross, report.refunds(), report.consumption(), thirdConsumption, reimbursed, pending);
    }
}
