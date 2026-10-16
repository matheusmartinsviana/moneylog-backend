package com.moneylog.core.finance.app.migration;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.thirdparty.domain.ShareMigrationReport;
import com.moneylog.core.thirdparty.domain.ShareMigrationReport.ExceptionReason;
import com.moneylog.core.thirdparty.domain.ShareMigrationReport.MonthEntry;
import com.moneylog.core.thirdparty.domain.ShareMigrationReport.PurchaseEntry;
import com.moneylog.core.thirdparty.domain.ShareMigrationReport.Status;
import com.moneylog.core.thirdparty.domain.ThirdPartyExpense;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;
import com.moneylog.core.transaction.domain.PurchaseShares;
import com.moneylog.core.transaction.domain.ShareLine;
import com.moneylog.core.transaction.domain.SplitMode;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;

public final class ShareMigrationPlanner {

    private static final Money ZERO = Money.zero(CurrencyCode.BRL);

    public record RefundRef(UUID id, LocalDate date, Money amount, UUID categoryId) {
    }

    public record Plan(ThirdPartyExpense mark, Transaction purchase, SplitMode mode, List<ShareLine> lines, List<RefundRef> refunds,
                       List<ThirdPartyReceipt> legacyReceipts, PurchaseEntry entry) {

        public boolean eligible() {
            return entry.status() == Status.MIGRATE;
        }
    }

    private ShareMigrationPlanner() {
    }

    public static List<Plan> plan(List<Transaction> transactions, List<ThirdPartyExpense> marks, List<ThirdPartyReceipt> receipts) {
        Map<UUID, Transaction> byId = transactions.stream().collect(Collectors.toMap(Transaction::id, t -> t, (a, b) -> a));
        List<Plan> plans = new ArrayList<>();
        for (ThirdPartyExpense mark : marks) {
            Transaction purchase = byId.get(mark.transactionId());
            if (purchase == null) {
                continue;
            }
            List<RefundRef> refunds = transactions.stream()
                    .filter(t -> t.isActive() && t.type() == TransactionType.REFUND && purchase.id().equals(t.refundOfId()))
                    .map(t -> new RefundRef(t.id(), t.date(), t.amount(), t.categoryId()))
                    .sorted(Comparator.comparing(RefundRef::date).thenComparing(RefundRef::id)).toList();
            List<ThirdPartyReceipt> legacy = receipts.stream()
                    .filter(r -> !r.ledgered() && purchase.id().equals(r.transactionId()))
                    .sorted(Comparator.comparing(ThirdPartyReceipt::id)).toList();
            plans.add(planOne(mark, purchase, refunds, legacy));
        }
        plans.sort(Comparator.comparing((Plan p) -> p.purchase().date()).thenComparing(p -> p.purchase().id()));
        return List.copyOf(plans);
    }

    public static ShareMigrationReport report(List<Plan> plans) {
        return ShareMigrationReport.of(plans.stream().map(Plan::entry).toList());
    }

    private static Plan planOne(ThirdPartyExpense mark, Transaction purchase, List<RefundRef> refunds, List<ThirdPartyReceipt> legacy) {
        Money refunded = Money.sum(refunds.stream().map(RefundRef::amount).toList(), CurrencyCode.BRL);
        Money legacyReceived = Money.sum(legacy.stream().map(ThirdPartyReceipt::amount).toList(), CurrencyCode.BRL);
        Money charged = purchase.type() == TransactionType.CARD_PURCHASE ? purchase.chargedAmount() : ZERO;
        ExceptionReason blocked = blocker(mark, purchase, refunds, legacy, charged);
        if (blocked != null) {
            return exception(mark, purchase, refunds, legacy, blocked, charged, refunded, legacyReceived);
        }
        Money third = mark.amount() != null ? mark.amount() : charged;
        if (third.isGreaterThan(charged)) {
            return exception(mark, purchase, refunds, legacy, ExceptionReason.MARK_EXCEEDS_CHARGED, charged, refunded, legacyReceived);
        }
        if (refunded.isGreaterThan(third)) {
            return exception(mark, purchase, refunds, legacy, ExceptionReason.REFUND_EXCEEDS_THIRD_PART, charged, refunded, legacyReceived);
        }
        if (refunded.add(legacyReceived).isGreaterThan(third)) {
            return exception(mark, purchase, refunds, legacy, ExceptionReason.RECEIVED_EXCEEDS_THIRD_PART, charged, refunded, legacyReceived);
        }
        List<TransactionInstallment> active = purchase.installments().stream().filter(i -> !i.isCanceled()).toList();
        SplitMode mode;
        List<ShareLine> lines;
        if (third.equals(charged)) {
            mode = SplitMode.BPS;
            lines = List.of(new ShareLine(mark.personId(), PurchaseShares.FULL_BPS, null));
        } else {
            long perInstallment = third.cents() / active.size();
            Money smallest = purchase.installments().stream().map(TransactionInstallment::amount).min(Money::compareTo).orElseThrow();
            if (third.cents() % active.size() != 0 || perInstallment < 1 || perInstallment >= smallest.cents()) {
                return exception(mark, purchase, refunds, legacy, ExceptionReason.MARK_NOT_REPRESENTABLE, charged, refunded, legacyReceived);
            }
            mode = SplitMode.AMOUNT;
            lines = List.of(new ShareLine(null, null, smallest.subtract(Money.ofCents(perInstallment, CurrencyCode.BRL))),
                    new ShareLine(mark.personId(), null, Money.ofCents(perInstallment, CurrencyCode.BRL)));
        }
        List<MonthEntry> months;
        try {
            months = months(purchase, refunds, PurchaseShares.create(mode, lines, smallestOf(purchase)), refunded.add(legacyReceived));
        } catch (DomainException e) {
            return exception(mark, purchase, refunds, legacy, ExceptionReason.MARK_NOT_REPRESENTABLE, charged, refunded, legacyReceived);
        }
        Money mine = sum(months, MonthEntry::consumptionMineAfter);
        Money pending = sum(months, MonthEntry::receivablePending);
        Money reimbursed = sum(months, MonthEntry::reimbursed);
        Money before = sum(months, MonthEntry::consumptionBefore);
        boolean holds = mine.add(pending).add(reimbursed).equals(charged) && before.equals(charged.subtract(refunded))
                && reimbursed.equals(refunded.add(legacyReceived)) && mine.equals(charged.subtract(third));
        if (!holds) {
            return exception(mark, purchase, refunds, legacy, ExceptionReason.INVARIANT_BROKEN, charged, refunded, legacyReceived);
        }
        PurchaseEntry entry = new PurchaseEntry(purchase.id(), mark.personId(), Status.MIGRATE, null, mode, charged, third, refunded,
                legacyReceived, months);
        return new Plan(mark, purchase, mode, lines, refunds, legacy, entry);
    }

    private static ExceptionReason blocker(ThirdPartyExpense mark, Transaction purchase, List<RefundRef> refunds,
                                           List<ThirdPartyReceipt> legacy, Money charged) {
        if (purchase.type() != TransactionType.CARD_PURCHASE) {
            return ExceptionReason.NOT_CARD_PURCHASE;
        }
        if (!purchase.isActive()) {
            return ExceptionReason.TRANSACTION_NOT_ACTIVE;
        }
        if (purchase.categoryId() == null) {
            return ExceptionReason.NO_CATEGORY;
        }
        if (purchase.purchaseShares() != null) {
            return ExceptionReason.ALREADY_SHARED;
        }
        if (!charged.isPositive()) {
            return ExceptionReason.NO_ACTIVE_INSTALLMENTS;
        }
        if (legacy.stream().anyMatch(r -> !mark.personId().equals(r.personId()))) {
            return ExceptionReason.RECEIPT_OF_OTHER_PERSON;
        }
        if (refunds.stream().anyMatch(r -> r.categoryId() == null)) {
            return ExceptionReason.INVARIANT_BROKEN;
        }
        return null;
    }

    private static Plan exception(ThirdPartyExpense mark, Transaction purchase, List<RefundRef> refunds, List<ThirdPartyReceipt> legacy,
                                  ExceptionReason reason, Money charged, Money refunded, Money legacyReceived) {
        Money third = mark.amount() != null ? mark.amount() : charged;
        PurchaseEntry entry = new PurchaseEntry(purchase.id(), mark.personId(), Status.EXCEPTION, reason, null, charged, third, refunded,
                legacyReceived, List.of());
        return new Plan(mark, purchase, null, List.of(), refunds, legacy, entry);
    }

    private static Money smallestOf(Transaction purchase) {
        return purchase.installments().stream().map(TransactionInstallment::amount).min(Money::compareTo).orElseThrow();
    }

    private static List<MonthEntry> months(Transaction purchase, List<RefundRef> refunds, PurchaseShares shares, Money received) {
        Map<Competence, Money[]> byMonth = new TreeMap<>();
        for (TransactionInstallment installment : purchase.installments()) {
            if (installment.isCanceled()) {
                continue;
            }
            Competence month = Competence.from(installment.effectiveDate());
            Money third = Money.sum(shares.split(installment.amount()).byPerson().stream().map(p -> p.amount()).toList(), CurrencyCode.BRL);
            Money[] acc = byMonth.computeIfAbsent(month, k -> new Money[] {ZERO, ZERO, ZERO, ZERO});
            acc[0] = acc[0].add(installment.amount());
            acc[1] = acc[1].add(installment.amount().subtract(third));
            acc[2] = acc[2].add(third);
        }
        for (RefundRef refund : refunds) {
            Money[] acc = byMonth.computeIfAbsent(Competence.from(refund.date()), k -> new Money[] {ZERO, ZERO, ZERO, ZERO});
            acc[0] = acc[0].subtract(refund.amount());
        }
        Money remaining = received;
        List<MonthEntry> months = new ArrayList<>();
        for (Map.Entry<Competence, Money[]> entry : byMonth.entrySet()) {
            Money owed = entry.getValue()[2];
            Money applied = remaining.isLessThan(owed) ? remaining : owed;
            remaining = remaining.subtract(applied);
            months.add(new MonthEntry(entry.getKey(), entry.getValue()[0], entry.getValue()[1], owed.subtract(applied), applied));
        }
        return List.copyOf(months);
    }

    private static Money sum(List<MonthEntry> months, java.util.function.Function<MonthEntry, Money> field) {
        return Money.sum(months.stream().map(field).toList(), CurrencyCode.BRL);
    }
}
