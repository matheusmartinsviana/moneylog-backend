package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;

public final class CardShareReceivables {

    private static final Money ZERO = Money.zero(CurrencyCode.BRL);

    public record MonthAmounts(Money owed, Money received) {

        public Money pending() {
            return owed.subtract(received);
        }
    }

    public record ShareKey(UUID installmentId, UUID personId) {
    }

    public record Part(UUID installmentId, int number, int total, Competence consumption, Competence dueMonth, Competence expectedMonth,
                       boolean overridden, Money owed, Money received) {

        public Money pending() {
            return owed.subtract(received);
        }

        public boolean editable() {
            return installmentId != null;
        }
    }

    public record PurchaseLine(UUID transactionId, UUID personId, Money owed, Money received, Map<Competence, MonthAmounts> months,
                               List<Part> parts) {

        public Map<Competence, MonthAmounts> expectedMonths() {
            Map<Competence, MonthAmounts> expected = new TreeMap<>();
            for (Part part : parts) {
                expected.merge(part.expectedMonth(), new MonthAmounts(part.owed(), part.received()),
                        (a, b) -> new MonthAmounts(a.owed().add(b.owed()), a.received().add(b.received())));
            }
            return expected;
        }

        public Money pending() {
            return received.isLessThan(owed) ? owed.subtract(received) : ZERO;
        }
    }

    private CardShareReceivables() {
    }

    public static Map<ShareKey, Competence> expectedMonthsOf(List<com.moneylog.core.thirdparty.domain.ReceivableExpectedMonth> stored) {
        Map<ShareKey, Competence> map = new HashMap<>();
        stored.forEach(e -> map.put(new ShareKey(e.installmentId(), e.personId()), e.month()));
        return map;
    }

    public static boolean isShareReceivable(LedgerEntry entry, Set<UUID> debtIds) {
        return entry.active() && entry.posting().bookType() == BookType.RECEIVABLE
                && entry.source().type() == PostingSourceType.TRANSACTION && !debtIds.contains(entry.posting().book().id());
    }

    public static Set<UUID> debtIds(FinancialRecords records) {
        return records.debts().stream().map(Debt::id).collect(Collectors.toSet());
    }

    public static Money inCompetence(FinancialRecords records, Ledger ledger, Competence competence) {
        Set<UUID> debtIds = debtIds(records);
        return Money.sum(ledger.inCompetence(BookType.RECEIVABLE, competence).stream()
                .filter(e -> isShareReceivable(e, debtIds)).map(e -> e.posting().amount()).toList(), CurrencyCode.BRL);
    }

    public static List<PurchaseLine> lines(FinancialRecords records, List<ThirdPartyReceipt> receipts) {
        return lines(records, receipts, Map.of());
    }

    private record RawPart(UUID installmentId, int number, int total, Competence consumption, Competence dueMonth, Competence expectedMonth,
                           boolean overridden, Money owed) {
    }

    private static Competence dueMonthOf(FinancialRecords records, Transaction tx, UUID installmentId) {
        if (installmentId == null || tx.cardId() == null) {
            return null;
        }
        TransactionInstallment installment = tx.installments().stream().filter(i -> i.id().equals(installmentId)).findFirst().orElse(null);
        CreditCard card = records.cards().stream().filter(c -> c.id().equals(tx.cardId())).findFirst().orElse(null);
        if (installment == null || card == null) {
            return null;
        }
        LocalDate due = card.calendar().periodClosingIn(YearMonth.from(installment.invoiceClosingDate())).dueDate();
        return Competence.from(due);
    }

    public static List<PurchaseLine> lines(FinancialRecords records, List<ThirdPartyReceipt> receipts, Map<ShareKey, Competence> overrides) {
        Set<UUID> debtIds = debtIds(records);
        Map<UUID, Transaction> txs = records.transactions().stream().collect(Collectors.toMap(Transaction::id, t -> t, (a, b) -> a));
        Map<String, Map<Competence, Money>> owedByKey = new HashMap<>();
        Map<String, UUID[]> keys = new HashMap<>();
        Map<String, List<RawPart>> rawParts = new HashMap<>();
        for (LedgerEntry e : records.ledger().entries()) {
            if (!isShareReceivable(e, debtIds)) {
                continue;
            }
            Transaction entryTx = txs.get(e.source().transactionId());
            if (entryTx == null || !entryTx.isActive()) {
                continue;
            }
            UUID txId = entryTx.type() == TransactionType.REFUND && entryTx.refundOfId() != null ? entryTx.refundOfId() : entryTx.id();
            if (!txs.containsKey(txId)) {
                continue;
            }
            UUID personId = e.posting().book().id();
            String key = txId + "/" + personId;
            keys.putIfAbsent(key, new UUID[] {txId, personId});
            owedByKey.computeIfAbsent(key, k -> new TreeMap<>()).merge(e.posting().competence(), e.posting().amount(), Money::add);
            UUID installmentId = e.source().installmentId();
            Competence consumption = e.posting().competence();
            Competence due = dueMonthOf(records, entryTx, installmentId);
            Competence defaultMonth = due == null ? consumption : due;
            Competence override = installmentId == null ? null : overrides.get(new ShareKey(installmentId, personId));
            TransactionInstallment installment = installmentId == null ? null
                    : entryTx.installments().stream().filter(i -> i.id().equals(installmentId)).findFirst().orElse(null);
            rawParts.computeIfAbsent(key, k -> new ArrayList<>()).add(new RawPart(installmentId, installment == null ? 0 : installment.number(),
                    installment == null ? 0 : installment.total(), consumption, defaultMonth, override == null ? defaultMonth : override,
                    override != null, e.posting().amount()));
        }
        List<PurchaseLine> lines = new ArrayList<>();
        for (Map.Entry<String, Map<Competence, Money>> entry : owedByKey.entrySet()) {
            UUID txId = keys.get(entry.getKey())[0];
            UUID personId = keys.get(entry.getKey())[1];
            Money receivedRaw = Money.sum(receipts.stream().filter(r -> r.ledgered() && txId.equals(r.transactionId()) && personId.equals(r.personId()))
                    .map(ThirdPartyReceipt::amount).toList(), CurrencyCode.BRL);
            Money netOwed = Money.sum(entry.getValue().values().stream().toList(), CurrencyCode.BRL);
            Money received = netOwed.isPositive() && netOwed.isLessThan(receivedRaw) ? netOwed : netOwed.isPositive() ? receivedRaw : ZERO;
            Money remaining = received;
            Money owedTotal = ZERO;
            Map<Competence, MonthAmounts> months = new TreeMap<>();
            List<Part> parts = new ArrayList<>();
            for (Map.Entry<Competence, Money> month : entry.getValue().entrySet()) {
                Money owed = month.getValue();
                owedTotal = owedTotal.add(owed);
                Money applied = owed.isPositive() ? (remaining.isLessThan(owed) ? remaining : owed) : ZERO;
                remaining = remaining.subtract(applied);
                months.put(month.getKey(), new MonthAmounts(owed, applied));
                Money left = applied;
                for (RawPart raw : rawParts.get(entry.getKey())) {
                    if (!raw.consumption().equals(month.getKey())) {
                        continue;
                    }
                    Money share = raw.owed().isPositive() ? (left.isLessThan(raw.owed()) ? left : raw.owed()) : ZERO;
                    left = left.subtract(share);
                    parts.add(new Part(raw.installmentId(), raw.number(), raw.total(), raw.consumption(), raw.dueMonth(), raw.expectedMonth(),
                            raw.overridden(), raw.owed(), share));
                }
            }
            lines.add(new PurchaseLine(txId, personId, owedTotal, received, months, List.copyOf(parts)));
        }
        lines.sort(Comparator.comparing((PurchaseLine l) -> txs.get(l.transactionId()).date()).thenComparing(PurchaseLine::transactionId)
                .thenComparing(PurchaseLine::personId));
        return lines;
    }

    public static Ledger grossLedger(FinancialRecords records) {
        Set<UUID> debtIds = debtIds(records);
        Map<UUID, Transaction> txs = records.transactions().stream().collect(Collectors.toMap(Transaction::id, t -> t, (a, b) -> a));
        List<LedgerEntry> base = records.entries();
        Map<PostingSource, Money> sharesBySource = new LinkedHashMap<>();
        Map<PostingSource, LedgerEntry> sampleBySource = new HashMap<>();
        for (LedgerEntry e : base) {
            if (isShareReceivable(e, debtIds)) {
                sharesBySource.merge(e.source(), e.posting().amount(), Money::add);
                sampleBySource.putIfAbsent(e.source(), e);
            }
        }
        if (sharesBySource.isEmpty()) {
            return new Ledger(base);
        }
        Set<PostingSource> merged = new HashSet<>();
        List<LedgerEntry> result = new ArrayList<>();
        for (LedgerEntry e : base) {
            Money share = sharesBySource.get(e.source());
            if (e.active() && share != null && e.posting().bookType() == BookType.EXPENSE && merged.add(e.source())) {
                result.add(new LedgerEntry(e.source(), new Posting(e.posting().book(), e.posting().amount().add(share),
                        e.posting().effectiveDate(), e.posting().competence()), true));
            } else {
                result.add(e);
            }
        }
        for (Map.Entry<PostingSource, Money> entry : sharesBySource.entrySet()) {
            Transaction tx = txs.get(entry.getKey().transactionId());
            if (merged.contains(entry.getKey()) || tx == null || tx.categoryId() == null || entry.getValue().isZero()) {
                continue;
            }
            Posting receivable = sampleBySource.get(entry.getKey()).posting();
            result.add(new LedgerEntry(entry.getKey(), new Posting(BookRef.expense(tx.categoryId()), entry.getValue(),
                    receivable.effectiveDate(), receivable.competence()), true));
        }
        return new Ledger(result);
    }
}
